package com.yinyue.player.data.fallback

import android.content.Context
import com.yinyue.domain.normalize.MetadataNormalizer
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.data.prefs.SettingsRepository
import com.yinyue.player.player.cache.MusicCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 兜底导入（Task 21，v3.0 §9）：
 * ①手动文件导入：文件名解析元数据 + 归一匹配（命中追加 Link，未命中新建实体），永久缓存永不淘汰；
 * ②抖音分享链接解析：单链接解析（尽力而为）→ 音频落盘 → MvLink(MV_PARSE) 挂实体。
 */
object FallbackImporter {

    data class ImportResult(val musicId: Long, val songName: String, val appended: Boolean)

    /** 文件名解析："歌手 - 歌名.mp3" / "歌名.mp3" */
    fun parseFileName(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()
        val parts = base.split(" - ", "－", "_", limit = 2)
        return if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to base
    }

    /** 手动文件导入：拷贝到 manual_store → 归一匹配 → 建实体 + LOCAL Link */
    suspend fun importLocalFile(context: Context, uri: android.net.Uri): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "导入-${System.currentTimeMillis()}.mp3"
        val dir = File(context.filesDir, "manual_store").apply { mkdirs() }
        val target = File(dir, displayName.substringAfterLast('/'))
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取文件")

        val (artistFromName, songFromName) = parseFileName(target.nameWithoutExtension)
        val db = AppDatabase.get(context)
        val songNorm = MetadataNormalizer.normalizeSong(songFromName)
        val artistNorm = MetadataNormalizer.normalizeArtist(artistFromName)
        val key = MetadataNormalizer.normalizedKey(songFromName, artistFromName)
        val existing = db.musicDao().findByNormalizedKey(key)
            ?: db.musicDao().findAllByArtistKey(artistNorm).firstOrNull {
                MetadataNormalizer.levenshtein(it.normalizedSong, songNorm) <= 1
            }
        val musicId: Long
        var appended = false
        if (existing != null) {
            musicId = existing.id
            appended = true
        } else {
            musicId = db.musicDao().insert(
                MusicEntity(
                    songName = songFromName,
                    artistName = artistFromName,
                    normalizedKey = key,
                    normalizedSong = songNorm,
                    normalizedArtist = artistNorm,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        // 查重：同一实体已有指向同一文件的链接就不再追加（重复导入同一文件很常见）
        val dupAudio = db.audioLinkDao().listByMusicOrdered(musicId)
            .any { it.localPath == target.absolutePath }
        if (!dupAudio) {
            db.audioLinkDao().insert(
                AudioLink(
                    musicId = musicId,
                    scriptId = "-",
                    platform = "local",
                    remoteTrackId = target.name,
                    qualityLevel = 1,
                    localPath = target.absolutePath,
                    cacheState = CacheState.LOCAL,
                    lastVerified = System.currentTimeMillis(),
                ),
            )
        }
        // 永久缓存：不参与 LRU（收藏保护之外的手动导入白名单）
        MusicCache.protect("manual:$musicId:${target.name}", true)
        ImportResult(musicId, songFromName.ifBlank { target.nameWithoutExtension }, appended || dupAudio)
    }

    /**
     * 抖音分享链接解析（尽力而为）：提取 aweme id → 多链路取媒体流 → 音频必下，画面按 [withVideo] 决定。
     * v1.0 §7.2 要求"抖音 URL 解析（音频/MV **二选一**）"：此前无脑连画面一起落盘，
     * 一个几十 MB 的视频会让用户磁盘莫名其妙变少，故按选择落盘。
     *
     * 链路顺序：自建中继（最稳）→ 分享页 RENDER_DATA/_ROUTER_DATA → 官方页面 → 旧接口。
     * 每一级失败都带明确原因，最后一次性告诉用户卡在哪一步（而不是笼统一句"解析失败"）。
     */
    suspend fun importDouyinShare(
        context: Context,
        shareUrl: String,
        withVideo: Boolean = false,
    ): ImportResult = withContext(Dispatchers.IO) {
        val relay = SettingsRepository.get(context).douyinRelay()
        val db = AppDatabase.get(context)
        val resolver = DouyinResolver()

        // 解析链路收在 DouyinResolver（候选 4）：导入模块只负责下载与落库
        val media = resolver.resolve(shareUrl, relay)
        val id = media.videoId
        if (media.audioUrl.isBlank() && media.videoUrl.isNullOrBlank()) {
            throw IllegalStateException("未取得媒体流地址（解析结果为空）")
        }

        // 找到即下载：音频必落盘；画面按"二选一"的选择落盘（默认只存音频，省空间）
        val dir = File(context.filesDir, "douyin_store").apply { mkdirs() }
        val audioUrl = media.audioUrl.takeIf { it.isNotBlank() }
        val videoUrl = media.videoUrl?.takeIf { it.isNotBlank() }
        // 部分作品没有独立原声：此时把视频流当音轨落盘，保证有声音能播
        val audioTarget = File(dir, if (audioUrl != null) "$id.audio.m4s" else "$id.audio.mp4")
        val videoTarget = File(dir, "$id.video.mp4")
        resolver.download(audioUrl ?: videoUrl!!, audioTarget)
        if (withVideo && videoUrl != null && videoUrl != audioUrl) {
            // 画面是可选项：下载失败只记日志，不影响音频已导入成功
            runCatching { resolver.download(videoUrl, videoTarget) }
                .onFailure { android.util.Log.w("YY-DY", "抖音画面下载失败：${it.message}") }
        }

        // 归一匹配：书名号优先，其次清洗分享标题，避免把话题/作者/广告当成歌名。
        val songName = com.yinyue.domain.normalize.NameCleaner.cleanMvTitle(media.title)
            .ifBlank { media.title.substringBefore(" ").ifBlank { media.title } }
        val key = MetadataNormalizer.normalizedKey(songName, media.author)
        val existing = db.musicDao().findByNormalizedKey(key)
        val musicId = existing?.id ?: db.musicDao().insert(
            MusicEntity(
                songName = songName,
                artistName = media.author,
                normalizedKey = key,
                normalizedSong = MetadataNormalizer.normalizeSong(songName),
                normalizedArtist = MetadataNormalizer.normalizeArtist(media.author),
                createdAt = System.currentTimeMillis(),
            ),
        )
        // 查重：同一实体的同一条抖音视频（或同一落盘音轨）不重复挂
        val audioPath = audioTarget.takeIf { it.exists() }?.absolutePath
        val dupMv = db.mvLinkDao().listByMusic(musicId).any {
            (it.sourcePlatform == "douyin_parse" && it.remoteId == id) ||
                (audioPath != null && it.localAudioPath == audioPath)
        }
        if (!dupMv) {
            db.mvLinkDao().insert(
                MvLink(
                    musicId = musicId,
                    sourcePlatform = "douyin_parse",
                    remoteId = id,
                    title = media.title,
                    upOwner = media.author,
                    localAudioPath = audioPath,
                    localVideoPath = videoTarget.takeIf { it.exists() }?.absolutePath,
                    cacheState = CacheState.LOCAL,
                    cachedAt = System.currentTimeMillis(),
                ),
            )
        }
        ImportResult(musicId, songName, existing != null || dupMv)
    }

    private fun queryDisplayName(context: Context, uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
