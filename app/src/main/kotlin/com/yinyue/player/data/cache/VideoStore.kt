package com.yinyue.player.data.cache

import android.content.Context
import com.yinyue.player.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * video_store（Task 19）：B站 audio.m4s / video.m4s 按实体分目录落盘；
 * 找到即下载（解析成功立即落盘）；LRU + 收藏保护（复用 CacheEvictorLogic）+ 双 store 联动清理。
 */
object VideoStore {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS) // 总超时上限，防止流式挂死
        .build()

    fun rootDir(context: Context): File = File(context.filesDir, "video_store")

    fun entityDir(context: Context, musicId: Long): File = File(rootDir(context), musicId.toString())

    fun audioPath(context: Context, musicId: Long): File = File(entityDir(context, musicId), "audio.m4s")

    fun videoPath(context: Context, musicId: Long): File = File(entityDir(context, musicId), "video.m4s")

    /** 元信息文件名（记录这条缓存是哪首歌，供下载管理显示友好名字） */
    private const val META_FILE = "meta.txt"

    /**
     * 写入元信息（歌名 - 歌手）。目录名仍是数字 key（LRU 淘汰按目录名解析，
     * 不能改名），但把人类可读的名字落到 meta.txt，下载管理据此显示。
     */
    fun writeMeta(context: Context, key: Long, displayName: String) {
        runCatching {
            val dir = entityDir(context, key)
            dir.mkdirs()
            File(dir, META_FILE).writeText(displayName)
        }
    }

    /** 读取元信息；没有则返回 null */
    fun readMeta(context: Context, dir: File): String? =
        runCatching { File(dir, META_FILE).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } }.getOrNull()
    /** 下载到指定文件（覆盖写） */
    suspend fun download(url: String, target: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.absolutePath + ".tmp")
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", "https://www.bilibili.com/") // B站 CDN 防盗链
                    .header("Origin", "https://www.bilibili.com")
                    .build(),
            )
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body?.byteStream()?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    } ?: throw IllegalStateException("empty body")
                }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            android.util.Log.i("YY-DL", "ok ${target.length()}B -> ${target.name}")
            target
        }.onFailure {
            // 不再静默：失败原因必须可见（此前 getOrNull 吞掉了 HTTP 码 / IO 异常）
            android.util.Log.w("YY-DL", "failed url=${url.take(140)} err=${it::class.java.simpleName}: ${it.message}")
        }.getOrNull()
    }

    fun totalSizeBytes(context: Context): Long =
        rootDir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** 目录级 LRU（按 lastModified 最旧优先）；收藏实体受保护 */
    suspend fun evictIfNeeded(context: Context, quotaBytes: Long, protectedMusicIds: Set<Long>): List<Long> = withContext(Dispatchers.IO) {
        val root = rootDir(context)
        if (!root.exists()) return@withContext emptyList()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()
        val spans = dirs.map {
            CacheEvictorLogic.SpanInfo(it.name, dirSize(it), it.lastModified())
        }.sortedBy { it.lastTouchOrder }
        val protectedKeys = protectedMusicIds.map { it.toString() }.toSet()
        val toEvict = CacheEvictorLogic.planEvictions(spans, protectedKeys, quotaBytes)
        toEvict.mapNotNull { key ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            entityDir(context, id).deleteRecursively()
            id
        }
    }

    /** 按实体清理（双 store 联动：video_store 目录 + audio_store 缓存键） */
    suspend fun clearEntity(context: Context, musicId: Long) {
        entityDir(context, musicId).deleteRecursively()
        com.yinyue.player.player.cache.MusicCache.clearEntityKeys(musicId)
    }

    fun clearAll(context: Context) {
        rootDir(context).deleteRecursively()
    }
}
