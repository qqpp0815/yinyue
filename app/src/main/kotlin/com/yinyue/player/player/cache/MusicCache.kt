package com.yinyue.player.player.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.yinyue.player.data.cache.CacheEvictorLogic
import com.yinyue.player.data.source.PlatformHttp
import java.io.File
import java.util.LinkedHashMap

/**
 * 音频缓存基座（Task 11）：audio_store/ 默认 5GB 可配，LRU + 收藏白名单。
 * video_store（Task 19）以同款 SimpleCache + ProtectedLruEvictor 另建实例，配额独立。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MusicCache {

    /** audio_store 默认配额 5GB（设置页可改，DataStore 接线在 Task 23） */
    @Volatile
    var audioQuotaBytes: Long = 5L * 1024 * 1024 * 1024

    /** 收藏 / 手动导入实体的 musicId 保护集（保护语义按实体，见 v3.0 §8） */
    private val protectedMusicIds: MutableSet<Long> = java.util.concurrent.CopyOnWriteArraySet()

    /** 真正参与淘汰判定的 key 集 = 受保护实体已注册过的缓存 key（播放 URL） */
    private val protectedKeys: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    @Volatile
    private var audioCache: SimpleCache? = null

    @Volatile
    private var evictor: ProtectedLruEvictor? = null

    fun audioCacheDir(context: Context): File = File(context.filesDir, "audio_store")

    fun audioCache(context: Context): SimpleCache = synchronized(this) {
        audioCache ?: run {
            val ev = ProtectedLruEvictor(audioQuotaBytes, protectedKeys)
            evictor = ev
            SimpleCache(audioCacheDir(context), ev, StandaloneDatabaseProvider(context))
                .also { audioCache = it }
        }
    }

    /** 设置页改配额后必须调这里：淘汰器持有的是配额副本，只改 audioQuotaBytes 不会生效 */
    fun updateQuota(newQuotaBytes: Long) {
        audioQuotaBytes = newQuotaBytes
        evictor?.updateQuota(newQuotaBytes)
    }

    /**
     * 保护/取消保护。
     * `fav:<musicId>` 与 `manual:<musicId>:<name>` 是**实体语义**的 key，而缓存 span 的 key 是播放 URL，
     * 直接把这些字符串塞进保护集永远不会命中任何 span（收藏保护=形同虚设）。
     * 故这里解析出 musicId，按实体把它已注册的 URL 加进保护集；其他字符串按字面 key 处理。
     */
    fun protect(key: String, protected: Boolean) {
        val musicId = entityMusicId(key)
        if (musicId != null) {
            if (protected) protectedMusicIds.add(musicId) else protectedMusicIds.remove(musicId)
            syncEntityProtection(musicId)
            return
        }
        if (protected) protectedKeys.add(key) else protectedKeys.remove(key)
    }

    private fun entityMusicId(key: String): Long? = when {
        key.startsWith("fav:") -> key.removePrefix("fav:").toLongOrNull()
        key.startsWith("manual:") -> key.removePrefix("manual:").substringBefore(':').toLongOrNull()
        else -> null
    }

    private fun syncEntityProtection(musicId: Long) {
        val urls = entityKeys[musicId] ?: return
        if (musicId in protectedMusicIds) protectedKeys.addAll(urls) else protectedKeys.removeAll(urls)
    }

    /** 实体 → 缓存 key 注册（双 store 联动清理依据） */
    private val entityKeys = java.util.concurrent.ConcurrentHashMap<Long, MutableSet<String>>()

    fun registerKey(musicId: Long, url: String) {
        entityKeys.getOrPut(musicId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(url)
        // 已收藏/已手动导入的实体：新注册的 URL 立刻纳入保护，否则"先收藏后播放"的缓存仍会被淘汰
        if (musicId in protectedMusicIds) protectedKeys.add(url)
    }

    fun clearEntityKeys(musicId: Long) {
        val keys = entityKeys.remove(musicId) ?: return
        protectedKeys.removeAll(keys)
        val cache = audioCache ?: return
        keys.forEach { key -> runCatching { cache.removeResource(key) } }
    }

    /** 仅供单测断言保护集（回归 bug：保护的是 fav:<id> 字符串，而缓存 span key 是 URL，永远匹配不上） */
    internal fun protectedKeysSnapshot(): Set<String> = protectedKeys.toSet()

    fun clearAll(context: Context) {
        synchronized(this) {
            audioCache?.release()
            audioCache = null
            audioCacheDir(context).deleteRecursively()
        }
    }

    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val http: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlatformHttp.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(emptyMap())
        // 上游必须是按 scheme 路由的 DefaultDataSource：
        // 纯 DefaultHttpDataSource 只认 http(s)，本地文件（下载/兜底导入/MV 本地双流）会打不开
        val upstream: DataSource.Factory = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        val biliUpstream: DataSource.Factory = androidx.media3.datasource.ResolvingDataSource.Factory(
            upstream,
            androidx.media3.datasource.ResolvingDataSource.Resolver { dataSpec ->
                val uri = dataSpec.uri.toString()
                val isBili = uri.contains("bilibili.com") ||
                    uri.contains("upos-") ||
                    uri.contains("akamaized.net")
                if (!isBili) {
                    dataSpec
                } else {
                    dataSpec.buildUpon()
                        .setHttpRequestHeaders(
                            mapOf(
                                "Referer" to com.yinyue.player.mv.bilibili.WbiSigner.REFERER,
                                "User-Agent" to com.yinyue.player.mv.bilibili.WbiSigner.USER_AGENT,
                            ),
                        )
                        .build()
                }
            },
        )
        return CacheDataSource.Factory()
            .setCache(audioCache(context))
            .setUpstreamDataSourceFactory(biliUpstream)
            .setCacheWriteDataSinkFactory(null) // 由 CacheDataSource 默认写入
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

/**
 * LRU + 收藏保护的自定义淘汰器：淘汰策略由纯函数 CacheEvictorLogic 决策。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProtectedLruEvictor(
    private var quotaBytes: Long,
    private val protectedKeys: Set<String>,
) : CacheEvictor {

    private val spans = LinkedHashMap<String, CacheSpan>(64, 0.75f, /* accessOrder = */ false)
    private var totalBytes = 0L

    override fun onCacheInitialized() {
        // SimpleCache 初始化后 spans 由 onSpanAdded 回放
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // 不预占额度；写入完成后的 onSpanAdded 触发淘汰
    }

    override fun requiresCacheSpanTouches(): Boolean = false

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        val old = spans.put(span.key, span)
        if (old != null) totalBytes -= old.length
        totalBytes += span.length
        evictIfNeeded(cache)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        val old = spans.remove(span.key)
        if (old != null) totalBytes -= old.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan.key)
        totalBytes -= oldSpan.length
        val old = spans.put(newSpan.key, newSpan)
        if (old != null) totalBytes -= old.length
        totalBytes += newSpan.length
    }

    fun updateQuota(newQuotaBytes: Long) {
        quotaBytes = newQuotaBytes
    }

    private fun evictIfNeeded(cache: Cache) {
        if (totalBytes <= quotaBytes) return
        val ordered = spans.entries
            .map { CacheEvictorLogic.SpanInfo(it.key, it.value.length, it.value.lastTouchTimestamp) }
            .sortedBy { it.lastTouchOrder }
        for (key in CacheEvictorLogic.planEvictions(ordered, protectedKeys, quotaBytes)) {
            val span = spans.remove(key) ?: continue
            runCatching { cache.removeSpan(span) }
            totalBytes -= span.length
        }
    }
}
