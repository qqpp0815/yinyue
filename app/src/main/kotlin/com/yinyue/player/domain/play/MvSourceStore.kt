package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.cache.VideoStore
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.domain.mv.MvRateLimitedException
import com.yinyue.player.domain.mv.MvSearchEngine
import java.io.File

/**
 * MV source module：封装 MvLink 的查找/落库、本地媒体定位与缓存 key。
 * interface 保持“找一条可解析的 MV”和“定位本地音轨/画面”，播放流程不感知目录细节。
 */
class MvSourceStore(private val context: Context) {
    private val db = AppDatabase.get(context)

    @Volatile
    var rateLimitMessage: String? = null
        private set

    suspend fun existingLinks(musicId: Long): List<MvLink> = db.mvLinkDao().listByMusic(musicId)

    suspend fun ensureLink(music: MusicEntity): MvLink? {
        existingLinks(music.id).firstOrNull()?.let { return it }
        val engine = MvSearchEngine.get(context)
        val strict = try {
            engine.searchMvFor(music)
        } catch (e: MvRateLimitedException) {
            rateLimitMessage = e.message
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (strict.isNotEmpty()) {
            rateLimitMessage = null
            return strict.first()
        }
        val relaxed = try {
            engine.searchMvRelaxed(music)
        } catch (e: MvRateLimitedException) {
            rateLimitMessage = e.message
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val first = relaxed.firstOrNull() ?: return null
        val newId = db.mvLinkDao().insert(
            MvLink(
                musicId = music.id,
                sourcePlatform = "bilibili",
                remoteId = first.candidate.id,
                title = first.candidate.title,
                upOwner = first.candidate.author,
                matchConfidence = first.confidence,
                cachedAt = System.currentTimeMillis(),
            ),
        )
        return db.mvLinkDao().listByMusic(music.id).firstOrNull { it.id == newId }
    }

    fun isManual(mv: MvLink): Boolean =
        mv.sourcePlatform == "douyin_parse" || mv.sourcePlatform == "local"

    fun cacheKey(mv: MvLink): Long =
        (mv.remoteId?.takeIf { it.isNotBlank() } ?: mv.id.toString()).hashCode().toLong()

    fun localAudio(mv: MvLink): File? {
        mv.localAudioPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) return file
        }
        return VideoStore.audioPath(context, cacheKey(mv))
            .takeIf { it.exists() && it.length() > 0 }
    }

    fun localVideo(mv: MvLink): File? {
        mv.localVideoPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) return file
        }
        return VideoStore.videoPath(context, cacheKey(mv))
            .takeIf { it.exists() && it.length() > 0 }
    }

    suspend fun markExpired(mv: MvLink) {
        db.mvLinkDao().updateCacheState(mv.id, CacheState.EXPIRED, System.currentTimeMillis())
    }

    suspend fun saveDownloadedMedia(
        mv: MvLink,
        audioUrl: String,
        videoUrl: String?,
    ) {
        val audioFile = VideoStore.download(audioUrl, VideoStore.audioPath(context, cacheKey(mv)))
        val videoFile = videoUrl?.let { VideoStore.download(it, VideoStore.videoPath(context, cacheKey(mv))) }
        if (audioFile != null) {
            db.mvLinkDao().updateLocalPaths(
                mv.id, audioFile.absolutePath, videoFile?.absolutePath ?: mv.localVideoPath, CacheState.LOCAL,
            )
        }
    }

    fun writeCacheMeta(mv: MvLink, displayName: String) {
        VideoStore.writeMeta(context, cacheKey(mv), displayName)
    }
}
