package com.yinyue.player.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.MoreExecutors

/**
 * 播放服务（Task 10）：单一 ExoPlayer 实例 + MediaSession。
 * 后台播放/通知栏/线控/audio focus 由 Media3 统一处理。
 * 断流重试与自动切源由 PlaybackCoordinator（Task 15）通过错误事件驱动。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val cacheFactory = com.yinyue.player.player.cache.MusicCache.cacheDataSourceFactory(this)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(MvAwareSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory), cacheFactory))
            // 快速起播：默认 LoadControl 需缓冲 50s 才播（B站 CDN 慢时等待过久）
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 5_000,
                        /* maxBufferMs = */ 30_000,
                        /* bufferForPlaybackMs = */ 1_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                    )
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // 拔耳机暂停
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        // 通知栏/耳机/车机的上一首下一首：命令走的是播放器自身。
        // 而本应用一次只挂一个 MediaItem（播放列表在应用侧队列里）→ 原生调用是空操作。
        // 这里包一层 ForwardingPlayer，把"切歌"类命令转接到应用侧播放队列（与 APP 内按钮/滑动同一路径）。
        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player, applicationContext)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        /** 供 Task 18 双模式（MergingMediaSource）与 Task 11 缓存接入的自定义工厂钩子 */
        @Volatile
        var mediaSourceDecorator: ((MediaSource) -> MediaSource)? = null

        fun buildMediaItem(url: String, title: String? = null, artist: String? = null, durationMs: Long? = null): MediaItem =
            MediaItem.Builder()
                .setUri(url)
                .apply {
                    // B站 DASH 音频分片（.m4s）需显式 mime 提示，否则提取器可能识别失败
                    if (url.contains(".m4s")) setMimeType(androidx.media3.common.MimeTypes.AUDIO_MP4)
                    if (durationMs != null && durationMs > 0) {
                        setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setExtras(android.os.Bundle().apply { putLong(MvAwareSourceFactory.EXTRA_DURATION_MS, durationMs) })
                                .build(),
                        )
                    }
                }
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .build(),
                )
                .build()
    }
}

/**
 * MV 双流感知的 MediaSourceFactory（Task 18）：
 * MediaItem.requestMetadata.extras 携带 videoUrl 时构建 MergingMediaSource(audio+video)。
 * 否则委托默认工厂（audio-only，含 CacheDataSource 离线优先）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MvAwareSourceFactory(
    private val delegate: androidx.media3.exoplayer.source.DefaultMediaSourceFactory,
    private val cacheFactory: androidx.media3.datasource.DataSource.Factory,
) : androidx.media3.exoplayer.source.MediaSourceFactory {

    override fun createMediaSource(mediaItem: MediaItem): androidx.media3.exoplayer.source.MediaSource {
        val videoUrl = mediaItem.requestMetadata.extras?.getString(EXTRA_VIDEO_URL).takeUnless { it.isNullOrEmpty() }
        val durationMs = mediaItem.requestMetadata.extras?.getLong(EXTRA_DURATION_MS, 0L) ?: 0L
        val base: androidx.media3.exoplayer.source.MediaSource = if (videoUrl == null) {
            delegate.createMediaSource(mediaItem)
        } else {
            val audio = delegate.createMediaSource(
                mediaItem.buildUpon().setMimeType(androidx.media3.common.MimeTypes.AUDIO_MP4).build(),
            )
            val video = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(videoUrl)
                        .apply {
                            // B站 DASH 视频分片（.m4s）需显式 mime 提示
                            if (videoUrl.contains(".m4s")) setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                        }
                        .build(),
                )
            androidx.media3.exoplayer.source.MergingMediaSource(audio, video)
        }
        // 用平台元数据时长裁剪：DASH 分片读出的时长常异常（几十分钟），以元数据为准保证进度条与 seek 正确
        return if (durationMs > 0) {
            androidx.media3.exoplayer.source.ClippingMediaSource(base, 0L, durationMs * 1000L)
        } else {
            base
        }
    }

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): androidx.media3.exoplayer.source.MediaSourceFactory =
        apply { delegate.setDrmSessionManagerProvider(drmSessionManagerProvider) }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
    ): androidx.media3.exoplayer.source.MediaSourceFactory =
        apply { delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

    override fun getSupportedTypes(): IntArray =
        delegate.supportedTypes + C.CONTENT_TYPE_OTHER

    companion object {
        const val EXTRA_VIDEO_URL = "videoUrl"
        const val EXTRA_DURATION_MS = "durationMs"
    }
}

/** 播放错误类型化事件（供切源状态机消费）。atMs/epoch 用于把错误归属到"本次起播尝试"，避免旧错误误伤新尝试。 */
data class PlaybackErrorEvent(
    val errorCode: Int,
    val message: String?,
    val atMs: Long = System.currentTimeMillis(),
    val epoch: Int = 0,
)

fun PlayerListenerForErrors(block: (PlaybackErrorEvent) -> Unit): Player.Listener =
    object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            block(PlaybackErrorEvent(error.errorCode, error.message))
        }
    }

fun MoreExecutorsDirect(): java.util.concurrent.Executor = MoreExecutors.directExecutor()

/**
 * 把切歌命令转接到应用侧播放队列的播放器包装：
 * 通知栏按钮、耳机按键、车机、蓝牙遥控走的都是播放器命令（seekToNext/Previous)。
 * 而应用是一次一个 MediaItem + 队列在 PlaybackQueue，故必须在此转接，否则那些按钮全是空操作。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueAwarePlayer(
    delegate: Player,
    private val appContext: android.content.Context,
) : androidx.media3.common.ForwardingPlayer(delegate) {

    private val coordinator get() = com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext)

    override fun seekToNext() { coordinator.nextTrack() }
    override fun seekToPrevious() { coordinator.previousTrack() }
    override fun seekToNextMediaItem() { coordinator.nextTrack() }
    override fun seekToPreviousMediaItem() { coordinator.previousTrack() }
    override fun hasNextMediaItem(): Boolean = com.yinyue.player.domain.play.PlaybackQueue.items.value.size > 1
    override fun hasPreviousMediaItem(): Boolean = com.yinyue.player.domain.play.PlaybackQueue.items.value.size > 1
}
