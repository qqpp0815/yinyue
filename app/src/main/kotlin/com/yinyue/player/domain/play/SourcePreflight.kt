package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.script.ScriptManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * 搜索后的后台预热（用户要求："搜索歌曲/歌手时就在后台按音源优先级排序好"）。
 *
 * 做法：搜索结果落库后，在后台按「外置脚本优先级 → 手动 > 本地 > 在线 → 优先级分」的顺序，
 * 逐首尝试取链；**第一条成功的链接即写回该实体的默认源**，同时把可用组合写进解析器缓存。
 * 这样用户点歌时：命中锁定直链/组合缓存 → 秒起播；若要换源，音源列表里已经排好可用顺序。
 *
 * 约束：仅预热前 [MAX_SONGS] 首、**并发 [CONCURRENCY] 首同时进行**、可被新的搜索取消；
 * 不产生任何平台批量请求（请求量 = 每个源最多 1 次，与"点播时再取链"相同，只是提前发生）。
 */
object SourcePreflight {

    /** 预热歌曲数上限。此前只有 8 首且串行，排在第 9 首之后的歌点播时仍是现场取链（体感慢）。 */
    private const val MAX_SONGS = 20

    /**
     * 并行度。不同歌曲的取链网络等待可以重叠；同一 QuickJS 引擎内部仍串行，
     * 6 是速度与弱网压力的平衡值。
     */
    private const val CONCURRENCY = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 搜索完成后调用；会取消上一轮预热 */
    fun warmUp(context: Context, musicIds: List<Long>) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            val db = AppDatabase.get(app)
            val manager = ScriptManager.get(app)
            val scripts = manager.enabledScripts()
            if (scripts.isEmpty()) {
                // 没有脚本：取链必然失败，不做无谓请求（留一条日志，便于确认预热是否真的在跑）
                android.util.Log.i("YY-WARM", "后台预热跳过：没有启用中的音源脚本")
                return@launch
            }
            android.util.Log.i("YY-WARM", "后台预热开始：${musicIds.size} 首（上限 $MAX_SONGS，并发 $CONCURRENCY）")

            // 先把引擎加载好（热路径刻意不加载，避免被自禁用脚本拖死），再读平台优先级；
            // 失败的脚本走冷却期，不会每轮都等满 12s
            scripts.forEach { runCatching { manager.ensureEngine(it.id) } }
            manager.refreshScriptsSnapshot()
            val priorities = manager.platformPriorities()

            val resolver = MusicUrlResolver.get(app)
            val semaphore = Semaphore(CONCURRENCY)
            val ids = musicIds.take(MAX_SONGS)
            coroutineScope {
                ids.forEach { id ->
                    launch {
                        semaphore.withPermit {
                            // 单首失败不影响其它歌（弱网/单源失效很常见）
                            runCatching { warmOne(db, resolver, priorities, id) }
                        }
                    }
                }
            }
        }
    }

    /** 单首预热：优先复用锁定直链，否则按源序逐条解析并锁定第一条可用源 */
    private suspend fun warmOne(
        db: AppDatabase,
        resolver: MusicUrlResolver,
        priorities: Map<String, Int>,
        musicId: Long,
    ) {
        val music = db.musicDao().findById(musicId) ?: return
        val links = SourceOrdering.ordered(
            links = db.audioLinkDao().listValidByMusicOrdered(musicId),
            platformPriority = priorities,
            localFileExists = { it.localPath?.let { p -> File(p).exists() } == true },
        )
        // 已是本地缓存：无需预热（本地文件 0 延迟起播）
        val hasLocal = links.any {
            it.cacheState == CacheState.LOCAL && it.localPath?.let { p -> File(p).exists() } == true
        }
        if (hasLocal) return
        val candidate = links.firstOrNull { link ->
            MusicUrlResolver.cachedCombo(link.id) != null || MusicUrlResolver.hasFreshLock(link)
        } ?: links.firstNotNullOfOrNull { link ->
            runCatching { resolver.prewarm(link, music) }
                .getOrNull()
                ?.let { link }
        }
        if (candidate != null) {
            writeDefaultSource(db, music, candidate)
        }
    }

    private suspend fun writeDefaultSource(
        db: AppDatabase,
        music: com.yinyue.player.data.db.entity.MusicEntity,
        link: com.yinyue.player.data.db.entity.AudioLink,
    ) {
        val key = "${link.platform}:${link.remoteTrackId}"
        if (music.defaultSourceId != key) {
            db.musicDao().update(music.copy(defaultSourceId = key))
        }
        android.util.Log.i("YY-WARM", "已锁定最优源：${music.songName} → ${link.platform}")
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * 下一首预热（A：切歌秒起的关键）：当前曲起播成功后调用，
     * 后台把播放队列中紧随其后的几首（Audio 项）也锁定好最优源。
     * 这样用户点「下一首」时命中锁定直链 → 直接起播，不再现场取链。
     */
    fun warmNext(context: Context) {
        val items = com.yinyue.player.domain.play.PlaybackQueue.items.value
        val idx = com.yinyue.player.domain.play.PlaybackQueue.currentIndex.value
        if (items.isEmpty() || idx < 0) return
        val nextIds = ArrayList<Long>(3)
        var i = idx + 1
        var scanned = 0
        while (nextIds.size < 3 && scanned < items.size) {
            val item = items[i % items.size]
            if (item is com.yinyue.player.domain.play.PlaybackQueue.Item.Audio) nextIds.add(item.musicId)
            i++
            scanned++
        }
        if (nextIds.isNotEmpty()) warmUp(context, nextIds)
    }
}
