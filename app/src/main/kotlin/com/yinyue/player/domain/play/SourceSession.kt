package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.script.ScriptManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private fun localFileOf(link: AudioLink): File? {
    val path = link.localPath?.takeIf { it.isNotBlank() } ?: return null
    val file = File(path)
    return if (file.exists() && file.length() > 0) file else null
}

/**
 * 源健康收口模块。
 *
 * AudioLink.cacheState 是最近一次解析结果的 UI 状态缓存，不是永久判决；
 * 竞速预算、探测硬等待、脚本引擎冷却、DNS/NAT 抖动都属于临时失败，需要后台复核自愈。
 */
object SourceSession {

    private const val AUTO_RECHECK_DELAY_MS = 2_000L
    const val MAX_BOOT_RECHECK_MS = 20_000L
    const val MAX_RECHECK_MS = 4_000L
    private const val MAX_RETRIES = 3
    private const val RETRY_INTERVAL_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val seq = AtomicLong(0)

    /**
     * 播放终态兜底：后台慢慢复核，而不是把本次失败写回全库。
     * `musicId == 0` 表示全库范围内的过期状态都可复核。
     */
    fun scheduleRecheck(context: Context, musicId: Long, reason: String = "playback-failed") {
        val app = context.applicationContext
        val token = seq.incrementAndGet()
        scope.launch {
            delay(AUTO_RECHECK_DELAY_MS)
            mutex.withLock {
                if (token != seq.get()) return@withLock
                android.util.Log.i("YY-SOURCE", "后台复核开始 reason=$reason musicId=$musicId")
                val db = AppDatabase.get(app)
                val links = if (musicId == 0L) {
                    db.audioLinkDao().listExpired().take(20)
                } else {
                    db.audioLinkDao().listByMusicOrdered(musicId)
                        .filter { it.cacheState == CacheState.EXPIRED && it.localPath.isNullOrBlank() }
                }
                recheck(app, db, links, budgetMs = MAX_RECHECK_MS, retries = 1)
            }
        }
    }

    /** 启动/网络恢复入口：清旧误判，让健康源重新进入可用状态。 */
    suspend fun recover(context: Context) {
        val app = context.applicationContext
        val db = AppDatabase.get(app)
        val candidates = db.audioLinkDao().listExpired()
            .filter { it.localPath.isNullOrBlank() }
            .filter { it.lastVerified == null || System.currentTimeMillis() - it.lastVerified > MAX_BOOT_RECHECK_MS }
            .take(20)
        mutex.withLock {
            android.util.Log.i("YY-SOURCE", "启动清理误判：${candidates.size} 条")
            recheck(app, db, candidates, budgetMs = MAX_BOOT_RECHECK_MS, retries = MAX_RETRIES)
        }
    }

    /** 注册网络恢复回调；模拟器/真机熄屏、切网后恢复时自动自愈。 */
    fun observeNetwork(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            manager.registerNetworkCallback(
                request,
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        scope.launch { runCatching { recover(app) } }
                    }
                },
            )
        }
    }

    suspend fun recheck(
        context: Context,
        db: AppDatabase,
        links: List<AudioLink>,
        budgetMs: Long,
        retries: Int,
    ) {
        if (links.isEmpty()) {
            android.util.Log.i("YY-SOURCE", "后台复核结束 剩余=0")
            return
        }
        ScriptManager.get(context).ensureEngines(
            ScriptManager.get(context).enabledScripts().map { it.id },
            budgetMs = budgetMs,
        )
        val grouped = links.groupBy { it.musicId }
        val resolver = MusicUrlResolver.get(context)
        val songs = HashMap<Long, MusicEntity>()
        repeat(retries) { attempt ->
            var remaining = 0
            coroutineScope {
                grouped.forEach { (musicId, songLinks) ->
                    launch {
                        val valid = songLinks.filter { it.localPath.isNullOrBlank() }
                        if (valid.isEmpty()) return@launch
                        val music = songs.getOrPut(musicId) { db.musicDao().findById(musicId) ?: return@launch }
                        android.util.Log.i("YY-SOURCE", "复核候选 $musicId=${valid.size} 条，预算 ${MAX_RECHECK_MS}ms")
                        val resolved = resolver.resolveFirstPlayable(valid, music, batchSize = valid.size, budgetMs = MAX_RECHECK_MS)
                        if (resolved != null) {
                            val winner = resolved.first
                            SourceHealth.markPlayable(context, resolved.first)
                            android.util.Log.i("YY-SOURCE", "复核成功 $musicId ${winner.platform} → REMOTE")
                        } else {
                            android.util.Log.i("YY-SOURCE", "复核失败 $musicId=${valid.size} 条")
                            remaining++
                        }
                    }
                }
            }
            android.util.Log.i("YY-SOURCE", "后台复核结束 attempt=${attempt + 1} 剩余=$remaining")
            if (remaining == 0 || attempt == retries - 1) return
            delay(RETRY_INTERVAL_MS)
        }
    }

}
