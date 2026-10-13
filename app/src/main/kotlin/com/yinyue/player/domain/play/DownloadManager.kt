package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.cache.VideoStore
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载（离线缓存）：把当前音源/ MV 拉到本地并记录 localPath，
 * 之后播放永远本地优先（存文件不存链接）。收藏项不参与 LRU 淘汰。
 */
object DownloadManager {

    data class Result(val ok: Boolean, val message: String)

    /** 下载歌曲音频：按优先级取第一个可用音源 → 落盘 → 回写 AudioLink.localPath */
    suspend fun downloadMusic(context: Context, musicId: Long): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val music = db.musicDao().findById(musicId)
            ?: return@withContext Result(false, "歌曲不存在")
        val links = db.audioLinkDao().listByMusicOrdered(musicId)
        if (links.isEmpty()) return@withContext Result(false, "没有可用音源")

        val resolver = MusicUrlResolver.get(context)
        for (link in links) {
            // 已有本地文件
            link.localPath?.let { p -> if (File(p).exists()) return@withContext Result(true, "已在本地") }
            val resolved = resolver.resolve(link, music)
            if (resolved == null) {
                android.util.Log.i("YY-DL", "resolve 失败，跳过 ${link.platform}:${link.remoteTrackId}")
                continue
            }
            // 注意：不能写进 audio_store——那是 ExoPlayer SimpleCache 的目录，
            // 缓存初始化/淘汰会把非缓存文件清掉（实测下载的文件会被删除，导致"下载了却播不了"）
            val dir = File(context.filesDir, "audio_downloads").apply { mkdirs() }
            val target = File(dir, "${music.songName} - ${music.artistName} [${link.platform}_${musicId}].audio")
            val saved = VideoStore.download(resolved.url, target)
            if (saved == null) {
                android.util.Log.i("YY-DL", "落盘失败，跳过 ${link.platform}（${resolved.quality.lxName}）")
                continue
            }
            SourceHealth.markLocal(context, link.id, saved.absolutePath)
            // 收藏项：缓存不参与淘汰
            if (music.favorite) {
                com.yinyue.player.player.cache.MusicCache.protect("fav:${musicId}", true)
            }
            return@withContext Result(true, "已下载（${link.platform} · ${resolved.quality.lxName}）")
        }
        Result(false, "下载失败：无可用音源或取链失败（需已导入音源脚本）")
    }

    /** 下载 MV：音轨必下（用于离线听），画面按需（需「缓存 MV 画面」及视频配额 > 0） */
    suspend fun downloadMv(
        context: Context,
        bvid: String,
        title: String,
        withVideo: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val info = try {
            com.yinyue.player.mv.bilibili.BiliClient().resolvePlayUrl(bvid)
        } catch (e: Exception) {
            return@withContext Result(false, "解析失败：${e.message?.take(40)}")
        }
        val key = bvid.hashCode().toLong()
        val audioFile = VideoStore.download(info.audioUrl, VideoStore.audioPath(context, key))
            ?: return@withContext Result(false, "音轨下载失败")
        var videoMsg = ""
        val allowVideo = withVideo && VideoCacheJanitor.shouldCacheVideo(
            context, SettingsRepository.get(context).cacheMvVideo(),
        )
        if (allowVideo && info.videoUrl != null) {
            val v = VideoStore.download(info.videoUrl, VideoStore.videoPath(context, key))
            videoMsg = if (v != null) "，画面已缓存" else "，画面下载失败"
        } else if (withVideo) {
            videoMsg = "，画面未缓存（视频配额为 0 或已关闭「缓存 MV 画面」）"
        }
        VideoStore.writeMeta(context, key, title)
        VideoCacheJanitor.enforce(context)
        Result(true, "MV 已缓存$videoMsg")
    }
}
