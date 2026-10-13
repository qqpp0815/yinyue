package com.yinyue.player.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI 侧播放状态（播放页/迷你条订阅）。
 *
 * **播放位置不在这里**：位置每 200ms 变一次，若放进本状态，任何订阅它的界面
 * （播放页整屏：黑胶动画/歌词/进度条）都会每秒重组 5 次，拖动进度条就会掉帧。
 * 位置单独走 [PlayerController.position]，由真正需要它的组件各自订阅（局部重组）。
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val currentUrl: String? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val buffering: Boolean = false,
    /** 已缓冲位置：用于区分"慢但在下数据"与"完全没有数据"（起播失败判定） */
    val bufferedPositionMs: Long = 0,
    /**
     * 播放纪元：每次 playUrl/playMv/stopAndClear 递增。
     * 起播成功/失败判定必须绑定到"本次尝试"的纪元，否则上一首歌还在播时，
     * isPlaying 的旧值会让 awaitPlaybackStart 误判新歌已起播（切歌竞态）。
     */
    val epoch: Int = 0,
)

/**
 * UI ↔ MediaSession 桥（Task 10）。
 * connect() 幂等；状态经 200ms 轮询 + 事件监听双通道同步
 * （200ms 是为了进度条跟手，位置走独立流，不会引发整屏重组）。
 */
object PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    /** 播放位置（毫秒）独立流：只有进度条/歌词这类真需要位置的组件订阅它 */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    private val _errors = MutableSharedFlow<PlaybackErrorEvent>(extraBufferCapacity = 16)
    val errors: SharedFlow<PlaybackErrorEvent> = _errors

    /** 一首播完（STATE_ENDED）：供队列自动续播（此前没有任何人监听，播完就停） */
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val ended: SharedFlow<Unit> = _ended

    /**
     * 最近一次播放错误（带重放）。
     * 起播失败判定必须能看到"订阅之前"就发生的错误（如 setMediaItem 后立刻 404），
     * 否则 SharedFlow（无重放）会漏掉，导致既不切源也不提示。每次 playUrl/playMv 前清空。
     */
    private val _lastError = MutableStateFlow<PlaybackErrorEvent?>(null)
    val lastError: StateFlow<PlaybackErrorEvent?> = _lastError

    /** 位置轮询间隔：200ms 兼顾跟手与开销（位置已独立成流，不会触发整屏重组） */
    private const val POSITION_POLL_MS = 200L

    fun clearLastError() {
        _lastError.value = null
    }

    /** 单调递增的播放纪元（见 PlayerUiState.epoch 注释） */
    private val epochCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private fun nextEpoch(): Int = epochCounter.incrementAndGet()

    private var controller: MediaController? = null
    private var connected = false

    fun connect(context: Context) {
        if (connected) return
        connected = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                connected = false
                return@addListener
            }
            controller = c
            attachedView?.player = c // 连接完成时补绑（PlayerView 先于 controller 就绪的场景）
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    android.util.Log.i(
                        "YY-DEBUG",
                        "player state=${when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> playbackState.toString()
                        }} buffered=${c.bufferedPosition}",
                    )
                    _state.value = _state.value.copy(
                        buffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = if (c.duration > 0) c.duration else 0,
                    )
                    if (playbackState == Player.STATE_ENDED) _ended.tryEmit(Unit)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _state.value = _state.value.copy(currentUrl = mediaItem?.localConfiguration?.uri?.toString())
                }
                override fun onRepeatModeChanged(repeatMode: Int) {
                    _state.value = _state.value.copy(repeatMode = repeatMode)
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("YY-DEBUG", "ExoPlayer error: ${error.errorCodeName} ${error.message}", error)
                    // 错误归属当前纪元：切歌/切源时旧错误不能误伤新尝试
                    val event = PlaybackErrorEvent(
                        error.errorCode,
                        error.message,
                        atMs = System.currentTimeMillis(),
                        epoch = _state.value.epoch,
                    )
                    _lastError.value = event
                    _errors.tryEmit(event)
                }
            })
            // 位置轮询：位置写独立流（只让进度条/歌词重组），其余字段写 _state（StateFlow 值相等不发射）
            scope.launch {
                while (true) {
                    val cc = controller
                    if (cc != null) {
                        // seek 回落保护：播放器到位（或超时）前，位置保持在本地 seek 目标上
                        val held = seekHold.displayPosition(cc.currentPosition, System.currentTimeMillis())
                        if (held != null) {
                            _position.value = held
                            _state.value = _state.value.copy(
                                bufferedPositionMs = cc.bufferedPosition.coerceAtLeast(0),
                            )
                        } else if (_state.value.isPlaying) {
                            _position.value = cc.currentPosition.coerceAtLeast(0)
                            _state.value = _state.value.copy(
                                durationMs = if (cc.duration > 0) cc.duration else 0,
                                bufferedPositionMs = cc.bufferedPosition.coerceAtLeast(0),
                            )
                        } else {
                            // 未起播时也要刷新缓冲进度，用于起播失败判定
                            _state.value = _state.value.copy(
                                bufferedPositionMs = cc.bufferedPosition.coerceAtLeast(0),
                            )
                        }
                    }
                    delay(POSITION_POLL_MS)
                }
            }
        }, { it.run() })
    }

    /** 起播新音频；返回本次尝试的纪元（awaitPlaybackStart 用它区分旧状态） */
    fun playUrl(url: String, title: String? = null, artist: String? = null, durationMs: Long? = null): Int {
        val epoch = nextEpoch()
        // 换歌：位置从 0 开始（否则进度条会在新歌开头短暂停在上一首的位置）；清掉 seek 回落保护
        seekHold.reset()
        _position.value = 0
        onMain {
            val c = controller ?: return@onMain
            _state.value = _state.value.copy(
                epoch = epoch, bufferedPositionMs = 0, isPlaying = false, durationMs = 0, buffering = true,
            )
            c.setMediaItem(PlaybackService.buildMediaItem(url, title, artist, durationMs))
            c.prepare()
            c.play()
        }
        return epoch
    }

    /** MV 双流播放（Task 18）：audio+video 合流（videoUrl 经 requestMetadata 传给服务的 MediaSourceFactory） */
    fun playMv(
        audioUrl: String,
        videoUrl: String?,
        title: String? = null,
        artist: String? = null,
        startPositionMs: Long = 0,
        durationMs: Long? = null,
    ): Int {
        val epoch = nextEpoch()
        seekHold.reset()
        _position.value = startPositionMs.coerceAtLeast(0)
        onMain {
            val c = controller ?: return@onMain
            _state.value = _state.value.copy(
                epoch = epoch, bufferedPositionMs = 0, isPlaying = false, durationMs = 0, buffering = true,
            )
            val extras = android.os.Bundle().apply {
                putString("videoUrl", videoUrl ?: "")
                if (durationMs != null && durationMs > 0) putLong("durationMs", durationMs)
            }
            val item = MediaItem.Builder()
                .setUri(audioUrl)
                .setMimeType(androidx.media3.common.MimeTypes.AUDIO_MP4)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(artist).build(),
                )
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
                .build()
            if (startPositionMs > 0) c.setMediaItem(item, startPositionMs) else c.setMediaItem(item)
            c.prepare()
            c.play()
        }
        return epoch
    }

    /** 暴露 MediaController 供 PlayerView 绑定（视频画面渲染）；必须在主线程使用 */
    @Volatile
    private var attachedView: androidx.media3.ui.PlayerView? = null

    fun bindToPlayerView(playerView: androidx.media3.ui.PlayerView) = onMain {
        attachedView = playerView
        playerView.player = controller
    }

    fun unbindPlayerView(playerView: androidx.media3.ui.PlayerView) = onMain {
        if (attachedView === playerView) attachedView = null
        playerView.player = null
    }

    /** MediaController 只能在主线程调用：统一在此切换（IO 线程调用方无需关心） */
    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            scope.launch { block() }
        }
    }

    fun togglePlayPause() = onMain {
        val c = controller ?: return@onMain
        if (c.isPlaying) c.pause() else c.play()
    }

    /** 暂停并清空当前条目（切歌/播放失败时避免"歌名与声音不符"）；同时推进纪元，中断等待旧起播的协程 */
    fun stopAndClear() = onMain {
        val c = controller ?: return@onMain
        _state.value = _state.value.copy(epoch = nextEpoch())
        c.stop()
        c.clearMediaItems()
        seekHold.reset()
        _position.value = 0
        _state.value = _state.value.copy(isPlaying = false, durationMs = 0, currentUrl = null)
    }

    /**
     * 本地 seek 目标（毫秒）与发起时刻。
     * seek 是异步的：松手后播放器仍会先回报**旧位置**，若直接采纳，进度条就"先弹回原处、再跳到拖动点"。
     * 这里记住目标位置，在播放器真正到位（或超时）之前一直采用本地目标值。
     */
    /** 进度条 seek 的回落保护（逻辑见 [SeekHold]） */
    private val seekHold = SeekHold()

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        seekHold.onSeek(target, System.currentTimeMillis())
        _position.value = target // 乐观更新：松手瞬间进度条就停在目标位置
        android.util.Log.i("YY-SPEED", "seek → ${target}ms（本地乐观更新，等待播放器到位）")
        onMain { controller?.seekTo(target) }
    }

    /**
     * 上一首/下一首：**由应用侧播放队列驱动**。
     * 注意不能再用 seekToNextMediaItem()——本应用是一次只挂一个 MediaItem（播放列表在 PlaybackQueue 里），
     * 播放器自身没有列表，那个调用一直是空操作（切歌只能靠点列表条目）。队列由 [PlaybackCoordinator] 维护。
     */
    @Volatile
    var onNext: (() -> Unit)? = null

    @Volatile
    var onPrevious: (() -> Unit)? = null

    fun seekToNext() {
        onNext?.invoke()
    }

    fun seekToPrevious() {
        onPrevious?.invoke()
    }

    fun cycleRepeatMode() = onMain {
        val c = controller ?: return@onMain
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun release() = onMain {
        controller?.release()
        controller = null
        connected = false
    }
}









