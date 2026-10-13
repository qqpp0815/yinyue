package com.yinyue.player.domain.play

import android.content.Context
import android.os.PowerManager
import com.yinyue.domain.match.PriorityScorer
import com.yinyue.domain.normalize.NameCleaner
import com.yinyue.domain.play.AutoSkipGuard
import com.yinyue.domain.play.PlayMode
import com.yinyue.domain.play.PlayModeLogic
import com.yinyue.player.data.cache.VideoStore
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.data.db.entity.PlayRecord
import com.yinyue.player.data.prefs.SettingsRepository
import com.yinyue.player.mv.bilibili.BiliClient
import com.yinyue.player.player.PlayerController
import com.yinyue.player.player.cache.MusicCache
import com.yinyue.player.script.ScriptManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 当前播放状态（播放页 / 迷你条订阅） */
data class NowPlaying(
    val musicId: Long = 0,
    val songName: String = "",
    val artistName: String = "",
    val lyricText: String? = null,
    val linksSummary: String = "",
    val switching: Boolean = false,
    val exhausted: Boolean = false,
    val exhaustedReason: String = "",
    val mvMode: Boolean = false,
    /** 时长基准（平台元数据，毫秒）：AudioLink/DASH 分片读出的时长常不准，界面以此为准 */
    val durationMs: Long? = null,
    /** 歌词手动校准偏移（毫秒，正 = 歌词提前显示）：MV 有片头时用户可自行校准 */
    val lyricOffsetMs: Long = 0,
    /** 封面地址（平台元数据；为空时 UI 回退为黑胶/渐变占位） */
    val coverUrl: String? = null,
    /** 链接状态徽标（本地缓存 / 在线 / 已失效，v3.0 §11 播放页要求） */
    val linkState: String = "",
    /** MV 当前音轨 URL（后台自动降级为纯音频时复用，§7.1） */
    val mvAudioUrl: String? = null,
    /** 正在播放的 AudioLink id（音源列表据此标「播放中」；按平台判断会把同平台多条全部误标） */
    val playingLinkId: Long? = null,
)

/**
 * 播放协调器（v3.0 §7 切源状态机）。
 *
 * 音频线（音源1）：URL 失效 → 下一条 AudioLink（音质/脚本优先级/响应时间，无感续播）
 * → 全部失败 → 自动用音源2 的 B站音轨兜底（文档 F5「双源互为兜底」§7.2 状态机）
 * → 音轨也失败 → EXPIRED + 终态提示。
 *
 * MV 线（音源2/兜底）：独立播放 DASH 双流。
 */
class PlaybackCoordinator private constructor(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mvSourceStore = MvSourceStore(context.applicationContext)
    private val lyricsLoader = LyricsLoader(context.applicationContext, scope) { lyric ->
        _nowPlaying.value = _nowPlaying.value.copy(lyricText = lyric)
    }

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying

    private var currentMusicId: Long = 0

    /** 当前正在播的 MV（MV 没有 musicId，播完续播 / 单曲循环要靠这份引用） */
    private data class MvRef(val bvid: String, val title: String, val author: String, val coverUrl: String?)

    @Volatile
    private var currentMv: MvRef? = null

    /** 最近一次 B站风控退避提示（"B站退避中，剩余 Ns"），失败时透传到界面 */
    @Volatile
    private var lastMvRateLimitMessage: String? = null

    /**
     * 串行化播放操作：快速连续切歌 / 切源 / 切形态时取消上一轮起播协程，
     * 避免旧协程把新歌状态覆盖回去（切歌竞态）。
     */
    private var playJob: Job? = null

    /** 连续自动跳歌计数（起播成功清零；见 AutoSkipGuard） */
    @Volatile
    private var autoSkipFailCount: Int = 0

    @Volatile
    private var playMode: PlayMode = PlayMode.DEFAULT

    @Volatile
    private var fallbackProbeJob: Job? = null

    @Volatile
    private var fallbackProbeCandidate: MvLink? = null

    init {
        PlaybackQueue.onCurrentRemoved = {
            PlayerController.stopAndClear()
            _nowPlaying.value = _nowPlaying.value.copy(switching = false)
        }
        PlayerController.onNext = { nextTrack() }
        PlayerController.onPrevious = { previousTrack() }
        scope.launch {
            PlayerController.ended.collect { onTrackEnded() }
        }
        // 最近播放：起播且 musicId 变化时落一条记录（MV 不计入）
        scope.launch {
            var recordedId: Long = -1
            PlayerController.state.collect { st ->
                val id = _nowPlaying.value.musicId
                if (st.isPlaying && id > 0 && id != recordedId) {
                    recordedId = id
                    runCatching {
                        db.playRecordDao().record(PlayRecord(0, id, System.currentTimeMillis()))
                    }
                }
            }
        }
    }

    private fun launchPlay(block: suspend () -> Unit) {
        playJob?.cancel()
        playJob = scope.launch { withTransitionWakeLock { block() } }
    }

    private suspend fun recordPlayHistory(musicId: Long) {
        runCatching {
            db.playRecordDao().record(PlayRecord(0, musicId, System.currentTimeMillis()))
        }
    }

    private fun startFallbackProbe(music: MusicEntity) {
        fallbackProbeJob?.cancel()
        fallbackProbeCandidate = null
        fallbackProbeJob = scope.launch {
            fallbackProbeCandidate = runCatching { mvSourceStore.ensureLink(music) }.getOrNull()
        }
    }

    private fun cancelFallbackProbe() {
        fallbackProbeJob?.cancel()
        fallbackProbeJob = null
    }

    /**
     * 切歌期间持一段 PARTIAL_WAKE_LOCK：息屏/后台时 CPU 不被挂起，
     * 否则「后台一下午后切歌」会因调度冻结卡住十几秒。90s 上限兜底自动释放。
     */
    private suspend fun <T> withTransitionWakeLock(block: suspend () -> T): T {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yinyue:track-transition")
        runCatching { wl.acquire(90_000L) }
        try {
            return block()
        } finally {
            runCatching { if (wl.isHeld) wl.release() }
        }
    }

    private fun playQueueItem(item: PlaybackQueue.Item) {
        when (item) {
            is PlaybackQueue.Item.Audio -> play(item.musicId, 0)
            is PlaybackQueue.Item.Mv -> playMv(item.bvid, item.title)
        }
    }

    suspend fun reloadPlayMode() {
        playMode = PlayMode.fromName(SettingsRepository.get(context).playMode())
    }

    fun currentPlayMode(): PlayMode = playMode

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        scope.launch { SettingsRepository.get(context).setPlayMode(mode.name) }
    }

    /** 循环切换：列表循环 → 单曲循环 → 随机 → 顺序 → 列表循环（对齐落雪） */
    fun cyclePlayMode() {
        setPlayMode(
            when (playMode) {
                PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
                PlayMode.SINGLE_LOOP -> PlayMode.RANDOM
                PlayMode.RANDOM -> PlayMode.LIST
                else -> PlayMode.LIST_LOOP
            },
        )
    }
    fun nextTrack(): Boolean {
        val next = PlayModeLogic.nextIndex(
            PlaybackQueue.currentIndex.value,
            PlaybackQueue.items.value.size,
            playMode,
        ) ?: run {
            stopAtQueueEnd()
            return false
        }
        val item = PlaybackQueue.jumpTo(next) ?: return false
        playQueueItem(item)
        return true
    }

    fun previousTrack(): Boolean {
        val prev = PlayModeLogic.previousIndex(
            PlaybackQueue.currentIndex.value,
            PlaybackQueue.items.value.size,
            playMode,
        ) ?: run {
            stopAtQueueEnd()
            return false
        }
        val item = PlaybackQueue.jumpTo(prev) ?: return false
        playQueueItem(item)
        return true
    }

    private suspend fun onTrackEnded() {
        val mv = currentMv
        if (currentMusicId == 0L && mv == null) return
        when (playMode) {
            PlayMode.SINGLE_LOOP -> {
                if (mv != null) replayMv() else if (currentMusicId != 0L) play(currentMusicId, 0)
            }
            PlayMode.NONE -> stopAtQueueEnd()
            else -> {
                if (PlaybackQueue.items.value.isNotEmpty()) {
                    nextTrack()
                } else if (mv != null) {
                    if (playMode == PlayMode.LIST) stopAtQueueEnd() else replayMv()
                } else {
                    stopAtQueueEnd()
                }
            }
        }
    }

    /** MV 播完续播：纯音频形态且有音轨直链则复用（免二次解析），否则重走 MV 播放 */
    private fun replayMv() {
        val mv = currentMv ?: return
        val now = _nowPlaying.value
        val audioUrl = now.mvAudioUrl
        if (!now.mvMode && !audioUrl.isNullOrBlank()) {
            launchPlay {
                _nowPlaying.value = _nowPlaying.value.copy(switching = true)
                val epoch = PlayerController.playUrl(audioUrl, mv.title, mv.author, now.durationMs)
                if (PlaybackStartWaiter.wait(epoch, 2_500L)) {
                    autoSkipFailCount = 0
                    _nowPlaying.value = _nowPlaying.value.copy(linksSummary = "B站音轨", switching = false, exhausted = false)
                } else {
                    _nowPlaying.value = _nowPlaying.value.copy(switching = false)
                }
            }
        } else {
            playMv(mv.bvid, mv.title, mv.author, mv.coverUrl)
        }
    }

    private fun stopAtQueueEnd() {
        PlayerController.stopAndClear()
        _nowPlaying.value = _nowPlaying.value.copy(
            linksSummary = "",
            switching = false,
            exhausted = false,
            exhaustedReason = "",
        )
    }

    fun play(musicId: Long, resumePositionMs: Long = 0) {
        launchPlay { playInternal(musicId, resumePositionMs) }
    }

    fun playQueueItemAt(index: Int) {
        when (val item = PlaybackQueue.jumpTo(index)) {
            is PlaybackQueue.Item.Audio -> play(item.musicId)
            is PlaybackQueue.Item.Mv -> playMv(item.bvid, item.title, item.artist)
            null -> Unit
        }
    }

    fun playMv(bvid: String, title: String, author: String = "B站 MV", coverUrl: String? = null) {
        currentMv = MvRef(bvid, title, author, coverUrl)
        launchPlay { playMvInternal(bvid, title, author, coverUrl) }
    }

    fun switchTo(linkId: Long) {
        launchPlay { switchToInternal(linkId) }
    }

    fun setDefaultSource(musicId: Long, linkId: Long) {
        scope.launch {
            val link = db.audioLinkDao().listByMusicOrdered(musicId).firstOrNull { it.id == linkId } ?: return@launch
            val music = db.musicDao().findById(musicId) ?: return@launch
            db.musicDao().update(music.copy(defaultSourceId = "${link.platform}:${link.remoteTrackId}"))
        }
    }

    suspend fun isDefaultSource(musicId: Long, link: AudioLink): Boolean {
        val music = db.musicDao().findById(musicId) ?: return false
        return music.defaultSourceId == "${link.platform}:${link.remoteTrackId}"
    }

    fun toggleMvMode() {
        launchPlay { toggleMvModeInternal() }
    }

    fun adjustLyricOffset(deltaMs: Long) {
        _nowPlaying.value = _nowPlaying.value.copy(
            lyricOffsetMs = (_nowPlaying.value.lyricOffsetMs + deltaMs).coerceIn(-30_000L, 30_000L),
        )
    }

    fun resetLyricOffset() {
        _nowPlaying.value = _nowPlaying.value.copy(lyricOffsetMs = 0)
    }

    /** 后台/锁屏降级为纯音频：丢视频流，音频从当前进度续播（省流量省电） */
    fun downgradeToAudioOnly() {
        scope.launch {
            val now = _nowPlaying.value
            if (!now.mvMode) return@launch
            val audioUrl = now.mvAudioUrl ?: return@launch
            val pos = PlayerController.position.value
            android.util.Log.i("YinYue", "downgradeToAudioOnly: video dropped at ${pos}ms, audio keeps playing")
            val epoch = PlayerController.playUrl(audioUrl, now.songName, now.artistName, now.durationMs)
            if (pos > 0) PlayerController.seekTo(pos)
            if (!PlaybackStartWaiter.wait(epoch, 2_500L)) {
                android.util.Log.w("YinYue", "downgradeToAudioOnly failed; restore MV mode")
                _nowPlaying.value = now.copy(mvMode = true)
                return@launch
            }
            _nowPlaying.value = now.copy(
                linksSummary = "B站音轨（后台省流）",
                switching = false,
                mvMode = false,
                linkState = "在线",
            )
        }
    }
    /**
     * 链接演唱者是否匹配实体：以原唱为准 —— 原唱链接全保留，他人翻唱限量且不自动播放。
     * artistKey 缺失时用会话 rawCache 回填，取不到一律放行（不误杀）。
     */
    private fun linkArtistMatchesEntity(link: AudioLink, music: MusicEntity): Boolean {
        val entityKey = NameCleaner.coreKey(music.artistName)
        val linkKey = link.artistKey.takeIf { it.isNotBlank() }
            ?: MusicUrlResolver.rawCache[link.platform]?.get(link.remoteTrackId)?.artistName
                ?.let { NameCleaner.coreKey(it) }
            ?: return true
        if (entityKey.isBlank()) return true
        return linkKey == entityKey || linkKey.contains(entityKey) || entityKey.contains(linkKey)
    }

    private fun localFileOf(link: AudioLink): File? {
        val path = link.localPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * 起播主干：本地文件 → 快速竞速（并行解析+探测，取最先可播）→ 慢速全量补试 → B站音轨兜底。
     *
     * 性能目标：3~5s 内起播。并行检测绝不串行叠加；探测硬超时只降级放行，
     * 不当「死链」证据（弱网/证书/首包慢都可能刚好卡满预算）。
     */
    private suspend fun playInternal(musicId: Long, resumePositionMs: Long) {
        currentMv = null
        val sameSong = currentMusicId == musicId
        currentMusicId = musicId
        val fromIdx = PlaybackQueue.currentIndex.value
        if (PlaybackQueue.syncIndexToAudio(musicId)) {
            android.util.Log.i(
                "YY-QUEUE",
                "队列下标对齐 ${fromIdx + 1} → ${PlaybackQueue.currentIndex.value + 1}（musicId=$musicId）",
            )
        }
        val music = db.musicDao().findById(musicId) ?: return
        val bootT0 = System.currentTimeMillis()
        // 全局预算：本地文件是 0 延迟快路径，不占用；所有在线解析/起播都必须在 deadline 内完成
        val deadlineAt = bootT0 + TOTAL_BOOT_BUDGET_MS
        val autoSwitch = SettingsRepository.get(context).autoSwitchSource()

        val startPosition = when {
            resumePositionMs > 0 -> resumePositionMs
            sameSong -> PlayerController.position.value
            else -> 0L
        }
        if (!sameSong) PlayerController.stopAndClear()

        _nowPlaying.value = _nowPlaying.value.copy(
            musicId = musicId,
            songName = music.songName,
            artistName = music.artistName,
            switching = true,
            durationMs = music.durationMs,
            coverUrl = music.coverUrl,
        )
        lyricsLoader.beginTrack(musicId)

        val hasScript = ScriptManager.get(context).enabledScripts().isNotEmpty()
        if (hasScript) startFallbackProbe(music)
        val links0 = db.audioLinkDao().listValidByMusicOrdered(musicId)
        // 先提默认源：用户指定过默认音源的歌曲，优先从它开始（命中锁定直链则秒起播）
        val defKey = music.defaultSourceId
        val links1 = if (defKey != null) {
            val idx = links0.indexOfFirst { "${it.platform}:${it.remoteTrackId}" == defKey }
            if (idx > 0) listOf(links0[idx]) + links0.filterIndexed { i, _ -> i != idx } else links0
        } else {
            links0
        }
        val ordered = SourceOrdering.ordered(
            links1,
            ScriptManager.get(context).platformPriorities(),
            { localFileOf(it) != null },
        )
        // 排序后再提一次默认源（排序会打乱原顺序，两次都做才能保证默认源真正在最前）
        val links1b = if (defKey != null) {
            val idx = ordered.indexOfFirst { "${it.platform}:${it.remoteTrackId}" == defKey }
            if (idx > 0) listOf(ordered[idx]) + ordered.filterIndexed { i, _ -> i != idx } else ordered
        } else {
            ordered
        }

        val localLinks = links1b.filter { localFileOf(it) != null }
        val remoteOriginal = links1b
            .filter { localFileOf(it) == null && linkArtistMatchesEntity(it, music) }
            .take(8)
        val remoteCovers = links1b
            .filter { localFileOf(it) == null && !linkArtistMatchesEntity(it, music) }
            .take(3)
        var attemptedAny = 0
        val remoteCands = remoteOriginal + remoteCovers

        suspend fun playResolved(
            link: AudioLink,
            resolved: MusicUrlResolver.Resolved,
            startPos: Long,
            deadlineAt: Long,
        ): Boolean {
            MusicCache.registerKey(musicId, resolved.url)
            val epoch = PlayerController.playUrl(resolved.url, music.songName, music.artistName, music.durationMs)
            if (startPos > 0) PlayerController.seekTo(startPos)
            if (!awaitStartWithin(epoch, deadlineAt, AWAIT_START_TIMEOUT_MS, MIN_BOOT_AWAIT_MS)) return false
            autoSkipFailCount = 0
            cancelFallbackProbe()
            recordPlayHistory(musicId)
            val linkState = when (link.cacheState) {
                CacheState.LOCAL -> "本地缓存"
                CacheState.EXPIRED -> "已失效"
                else -> "在线"
            }
            _nowPlaying.value = _nowPlaying.value.copy(
                linksSummary = "${link.platform}@${resolved.quality.lxName}",
                switching = false,
                exhausted = false,
                linkState = linkState,
                playingLinkId = link.id,
            )
            android.util.Log.i(
                "YY-SPEED",
                "起播 ${System.currentTimeMillis() - bootT0}ms（脚本 ${link.platform}@${resolved.quality.lxName}）",
            )
            SourcePreflight.warmNext(context)
            prefetchMv(music)
            return true
        }
        // ① 本地文件（导入/缓存）：离线秒起播，优先级最高
        for (link in localLinks) {
            if (!autoSwitch && attemptedAny != 0) break
            attemptedAny = 1
            _nowPlaying.value = _nowPlaying.value.copy(switching = true)
            val localFile = localFileOf(link) ?: continue
            if (link.cacheState == CacheState.EXPIRED) {
                SourceHealth.restoreLocal(context, link.id, localFile.absolutePath)
            }
            android.util.Log.i("YY-DL", "播放本地文件 uri=file://${localFile.absolutePath}")
            val epoch = PlayerController.playUrl(
                "file://${localFile.absolutePath}",
                music.songName,
                music.artistName,
                music.durationMs,
            )
            if (startPosition > 0) PlayerController.seekTo(startPosition)
            if (!awaitStartWithin(epoch, deadlineAt, AWAIT_START_TIMEOUT_MS, MIN_BOOT_AWAIT_MS)) continue
            autoSkipFailCount = 0
            cancelFallbackProbe()
            recordPlayHistory(musicId)
            _nowPlaying.value = _nowPlaying.value.copy(
                linksSummary = if (link.platform == "local") "本地导入文件" else "本地缓存",
                switching = false,
                exhausted = false,
                linkState = "本地缓存",
                playingLinkId = link.id,
            )
            android.util.Log.i("YY-SPEED", "起播 ${System.currentTimeMillis() - bootT0}ms（本地文件）")
            SourcePreflight.warmNext(context)
            prefetchMv(music)
            return
        }

        // ② 快速竞速：并行解析+探测取最先可播（预算 2.4s）
        android.util.Log.i(
            "YY-SPEED",
            "候选准备 ${System.currentTimeMillis() - bootT0}ms（${remoteCands.size}条 脚本=$hasScript 自动切源=$autoSwitch）",
        )
        val raceLinks = if (autoSwitch) remoteCands else remoteCands.take(1)
        val raceT0 = System.currentTimeMillis()
        // 竞速占用 min(剩余预算, 2s)：给慢轮与起播留足余量，冷启动首播也不越过全局预算
        val raceBudget = budgetWithin(deadlineAt, FAST_RACE_BUDGET_MS, 400L)
        val winner = if (raceBudget == null) {
            android.util.Log.i("YY-SPEED", "竞速跳过：全局预算仅剩 ${remainingBootMs(deadlineAt)}ms")
            null
        } else {
            SourceResolution.fastRace(context, raceLinks, music, budgetMs = raceBudget)
        }
        android.util.Log.i(
            "YY-SPEED",
            "竞速解析 ${System.currentTimeMillis() - raceT0}ms → ${winner?.link?.platform ?: "无可播"}，候选=${raceLinks.size}",
        )
        val tried = HashSet<Long>()
        if (winner != null) {
            tried.add(winner.link.id)
            attemptedAny = 1
            _nowPlaying.value = _nowPlaying.value.copy(switching = true)
            if (playResolved(winner.link, winner.resolved, startPosition, deadlineAt)) return
            // 起播失败 ≠ 音源失效：只丢掉这条（可能已过期的）锁定直链，让下轮重新取链；
            // 不标 EXPIRED —— 否则"慢但可用"的源会被误判为失效而切到音源2（超时只降级放行的口径）。
            SourceHealth.clearLock(context, winner.link.id)

        }

        // ③ 慢速补试：并行全量检测，串行起播（最多 3 轮，且总时长不越过全局预算）。
        // 五平台同灭时第 1 轮往往是"探测窗口刚好撞上网络抖动"，第 2/3 轮给它们自证机会，
        // 避免预算一紧张就绕过音源1直接跳 B站兜底。
        var slowRoundsRun = 0
        if (autoSwitch) {
            var remaining = remoteCands.filter { it.id !in tried }
            var round = 0
            while (remaining.isNotEmpty() && round < 3) {
                val slowBudget = budgetWithin(deadlineAt, SLOW_RESOLVE_BUDGET_MS, MIN_SLOW_BUDGET_MS)
                if (slowBudget == null) {
                    android.util.Log.i(
                        "YY-SPEED",
                        "慢轮跳过：全局预算仅剩 ${remainingBootMs(deadlineAt)}ms（已试 ${tried.size} 条）",
                    )
                    break
                }
                round++
                val raceT1 = System.currentTimeMillis()
                val race = SourceResolution.slowRace(context, remaining, music, budgetMs = slowBudget)
                android.util.Log.i(
                    "YY-SPEED",
                    "慢轮#$round 检测 ${System.currentTimeMillis() - raceT1}ms → ${race.playable.size} 条可播（${remaining.size} 条候选）",
                )
                for (attempt in race.playable) {
                    val link = attempt.link
                    val resolved = attempt.resolved
                    tried.add(link.id)
                    attemptedAny = 1
                    _nowPlaying.value = _nowPlaying.value.copy(switching = true)
                    if (playResolved(link, resolved, startPosition, deadlineAt)) {
                        android.util.Log.i("YY-SPEED", "慢速补试成功，总耗时 ${System.currentTimeMillis() - bootT0}ms")
                        return
                    }
                    // 同上：起播失败只清锁定直链，不判死；解析层/探测层才是失效的唯一证据源
                    SourceHealth.clearLock(context, link.id)

                }
                remaining = remaining.filter { it.id !in race.finished }
            }
            slowRoundsRun = round
        }

        // ④ 兜底：音源1 已尝试且本轮解析/起播失败，就必须先试音源2，不能直接 autoSkip。
        // 跳切防误触交给 requestEpoch：同一首歌内快速操作只保留最后一次请求；
        // 换歌时旧歌的失败分支本身不会再进入当前播放状态。
        val canFallback =
            !autoSwitch || !hasScript || attemptedAny > 0 || slowRoundsRun >= MIN_FALLBACK_ROUNDS
        if (canFallback) {
            if (runCatching { playMvAudioFallback(music, startPosition) }.getOrDefault(false)) {
                autoSkipFailCount = 0
                // 兜底成功也要排复核：音源1 本轮可能是被预算截断/弱网误判"未确认"，
                // 后台复核成功即恢复 REMOTE，下一首/下次点歌就能回到音源1，而不是长期停留在兜底。
                SourceSession.scheduleRecheck(context, musicId, "fallback-success-recheck")
                return
            }
        }
        if (autoSwitch && !canFallback) {
            android.util.Log.i(
                "YY-SPEED",
                "兜底门禁：仅跑 $slowRoundsRun 轮慢轮（<2），跳过 B站兜底直接终态/autoSkip",
            )
        }
        if (hasScript) {
            SourceSession.scheduleRecheck(context, musicId)
            SourceSession.scheduleRecheck(context, musicId, "remote-state-recheck")
        }
        android.util.Log.i("YY-SPEED", "全部候选失败，用时 ${System.currentTimeMillis() - bootT0}ms")

        val reason = if (!hasScript) {
            "未导入音源脚本，请到「我的 → 音源管理」导入"
        } else {
            val mvLinks = mvSourceStore.existingLinks(musicId)
            if (mvLinks.isNotEmpty()) {
                "音源1 全部失效，但音源2 的 MV 仍可用：点右下「看MV」即可播放"
            } else {
                "全部音源已失效，请使用兜底导入"
            }
        }

        PlayerController.stopAndClear()
        val autoSkipEnabled = runCatching { SettingsRepository.get(context).autoSkipOnError() }.getOrDefault(true)
        if (autoSkipEnabled && AutoSkipGuard.shouldSkip(autoSkipFailCount, PlaybackQueue.items.value.size)) {
            autoSkipFailCount++
            android.util.Log.i("YY-SKIP", "「${music.songName}」无可用音源，自动切换下一首（连续第 $autoSkipFailCount 首）")
            if (nextTrack()) return
        } else {
            autoSkipFailCount = 0
        }
        _nowPlaying.value = _nowPlaying.value.copy(
            switching = false,
            exhausted = true,
            exhaustedReason = reason,
        )
    }
    /** MV 线：B站 DASH 双流（音轨+画面），解析成功即后台落盘缓存 */
    private suspend fun playMvInternal(bvid: String, title: String, author: String, coverUrl: String?) {
        val fromIdx = PlaybackQueue.currentIndex.value
        if (PlaybackQueue.syncIndexToMv(bvid)) {
            android.util.Log.i(
                "YY-QUEUE",
                "队列下标对齐(MV) ${fromIdx + 1} → ${PlaybackQueue.currentIndex.value + 1}（bvid=$bvid）",
            )
        }
        _nowPlaying.value = NowPlaying(
            musicId = 0,
            songName = title,
            artistName = author,
            switching = true,
            mvMode = true,
            coverUrl = coverUrl,
        )
        val info = runCatching { BiliClient().resolvePlayUrl(bvid) }.getOrElse { e ->
            _nowPlaying.value = _nowPlaying.value.copy(
                switching = false,
                exhausted = true,
                exhaustedReason = "MV 解析失败：${e.message?.take(60)}",
            )
            return
        }
        lyricsLoader.loadForMv(title, author, info.durationMs.takeIf { it > 0 })
        _nowPlaying.value = _nowPlaying.value.copy(
            durationMs = info.durationMs.takeIf { it > 0 },
            mvAudioUrl = info.audioUrl,
        )
        val epoch = PlayerController.playMv(
            info.audioUrl,
            info.videoUrl,
            title,
            author,
            0,
            info.durationMs.takeIf { it > 0 },
        )
        if (PlaybackStartWaiter.wait(epoch, 2_500L)) {
            autoSkipFailCount = 0
            _nowPlaying.value = _nowPlaying.value.copy(
                linksSummary = "B站 MV",
                switching = false,
                exhausted = false,
                linkState = "在线",
            )
            scope.launch {
                val key = bvid.hashCode().toLong()
                VideoStore.writeMeta(
                    context,
                    key,
                    listOf(title, author).filter { it.isNotBlank() }.joinToString(" - "),
                )
                VideoStore.download(info.audioUrl, VideoStore.audioPath(context, key))
                val wantVideo = VideoCacheJanitor.shouldCacheVideo(
                    context,
                    SettingsRepository.get(context).cacheMvVideo(),
                )
                val videoUrl = info.videoUrl
                if (wantVideo && videoUrl != null) {
                    VideoStore.download(videoUrl, VideoStore.videoPath(context, key))
                }
                VideoCacheJanitor.enforce(context)
            }
        } else {
            _nowPlaying.value = _nowPlaying.value.copy(switching = false)
        }
    }

    /** 音源列表手动切源：解析目标链接并原地续播，失败回退整曲重播 */
    private suspend fun switchToInternal(linkId: Long) {
        val link = db.audioLinkDao().listValidByMusicOrdered(currentMusicId).firstOrNull { it.id == linkId } ?: return
        val music = db.musicDao().findById(currentMusicId) ?: return
        val resumeAt = PlayerController.position.value
        _nowPlaying.value = _nowPlaying.value.copy(switching = true)
        val resolved = SourceResolution.resolveOne(context, link, music)
        if (resolved != null) {
            val epoch = PlayerController.playUrl(resolved.url, music.songName, music.artistName, music.durationMs)
            if (resumeAt > 0) PlayerController.seekTo(resumeAt)
            if (PlaybackStartWaiter.wait(epoch, 2_500L)) {
                _nowPlaying.value = _nowPlaying.value.copy(
                    linksSummary = "${link.platform}@${resolved.quality.lxName}",
                    switching = false,
                    playingLinkId = link.id,
                )
                return
            }
        }
        _nowPlaying.value = _nowPlaying.value.copy(switching = false)
        play(currentMusicId, 0)
    }

    /** MV 预取：播放音频时顺手确保该曲有 MV 链接，用户点「看MV」时不再现搜 */
    private fun prefetchMv(music: MusicEntity) {
        scope.launch {
            if (_nowPlaying.value.mvMode) return@launch
            runCatching {
                if (mvSourceStore.existingLinks(music.id).isEmpty()) {
                    android.util.Log.i("YinYue", "MV 预取：${music.songName} - ${music.artistName}")
                    mvSourceStore.ensureLink(music)
                }
            }
        }
    }
    /**
     * 音频 ↔ MV 形态切换：按「手动 > 本地缓存 > 在线」顺序挑源，
     * 本地音轨/画面优先（离线可播），其次 B站解析（成功即落盘）。
     */
    private suspend fun toggleMvModeInternal() {
        val musicId = currentMusicId
        if (musicId == 0L) return
        val position = PlayerController.position.value
        val music = db.musicDao().findById(musicId) ?: return
        val toMv = !_nowPlaying.value.mvMode
        _nowPlaying.value = _nowPlaying.value.copy(switching = true, mvMode = toMv)
        if (!toMv) {
            play(musicId, position)
            return
        }
        mvSourceStore.ensureLink(music)
        val links = mvSourceStore.existingLinks(musicId)
        if (links.isEmpty()) {
            _nowPlaying.value = _nowPlaying.value.copy(
                switching = false,
                exhausted = true,
                exhaustedReason = lastMvRateLimitMessage
                    ?: "未找到该歌曲的 MV，可到「搜索 → MV」单独选择",
            )
            return
        }
        val ordered = links.sortedByDescending {
            PriorityScorer.sourceTier(mvSourceStore.isManual(it), mvSourceStore.localAudio(it) != null)
        }
        val cacheVideo = VideoCacheJanitor.shouldCacheVideo(
            context,
            SettingsRepository.get(context).cacheMvVideo(),
        )
        for (mv in ordered) {
            val localAudio = mvSourceStore.localAudio(mv)
            if (localAudio != null) {
                val localVideo = mvSourceStore.localVideo(mv)
                val epoch = if (localVideo != null) {
                    PlayerController.playMv(
                        "file://${localAudio.absolutePath}",
                        "file://${localVideo.absolutePath}",
                        music.songName,
                        music.artistName,
                        position,
                        music.durationMs,
                    )
                } else {
                    val e = PlayerController.playUrl(
                        "file://${localAudio.absolutePath}",
                        music.songName,
                        music.artistName,
                        music.durationMs,
                    )
                    PlayerController.seekTo(position)
                    e
                }
                if (PlaybackStartWaiter.wait(epoch, 2_500L)) {
                    _nowPlaying.value = _nowPlaying.value.copy(
                        linksSummary = if (localVideo != null) "本地MV" else "本地音轨",
                        switching = false,
                        exhausted = false,
                        linkState = "本地缓存",
                        mvAudioUrl = "file://${localAudio.absolutePath}",
                    )
                    return
                }
            }
            val bvid = mv.remoteId?.takeIf { mv.sourcePlatform == "bilibili" } ?: continue
            val info = runCatching { BiliClient().resolvePlayUrl(bvid) }.getOrNull()
            if (info == null) {
                mvSourceStore.markExpired(mv)
                continue
            }
            val epoch = PlayerController.playMv(
                info.audioUrl,
                info.videoUrl,
                music.songName,
                music.artistName,
                position,
                info.durationMs.takeIf { it > 0 } ?: music.durationMs,
            )
            if (PlaybackStartWaiter.wait(epoch, 2_500L)) {
                _nowPlaying.value = _nowPlaying.value.copy(
                    linksSummary = "B站MV",
                    switching = false,
                    exhausted = false,
                    linkState = "在线",
                    mvAudioUrl = info.audioUrl,
                )
                scope.launch {
                    mvSourceStore.saveDownloadedMedia(mv, info.audioUrl, if (cacheVideo) info.videoUrl else null)
                    VideoCacheJanitor.enforce(context)
                }
                return
            }
            mvSourceStore.markExpired(mv)
        }
        _nowPlaying.value = _nowPlaying.value.copy(
            switching = false,
            exhausted = true,
            exhaustedReason = "全部 MV 源均无法播放（B站解析失败或本地文件缺失）",
        )
    }

    /**
     * 兜底：音源1 全部失效时用音源2 的 B站音轨续播（文档 F5「双源互为兜底」）。
     * 本地音轨优先；否则 5s 预算内解析 B站音频流。
     */
    private suspend fun playMvAudioFallback(music: MusicEntity, startPosition: Long): Boolean {
        android.util.Log.i("YY-SPEED", "B站兜底：开始 ${music.songName}")
        val mv: MvLink = fallbackProbeCandidate
            ?: mvSourceStore.existingLinks(music.id).firstOrNull()
            ?: mvSourceStore.ensureLink(music)
            ?: return false
        cancelFallbackProbe()
        val localAudio = mvSourceStore.localAudio(mv)
        android.util.Log.i("YY-SPEED", "B站兜底：MV=${mv.remoteId} 本地音频=${localAudio != null}")
        if (localAudio != null) {
            _nowPlaying.value = _nowPlaying.value.copy(switching = true)
            val epoch = PlayerController.playUrl(
                "file://${localAudio.absolutePath}",
                music.songName,
                music.artistName,
                music.durationMs,
            )
            if (startPosition > 0) PlayerController.seekTo(startPosition)
            if (PlaybackStartWaiter.wait(epoch, 2_500L)) {
                _nowPlaying.value = _nowPlaying.value.copy(
                    linksSummary = "B站音轨（本地兜底）",
                    switching = false,
                    exhausted = false,
                    linkState = "本地缓存",
                )
                return true
            }
        }
        val bvid = mv.remoteId?.takeIf { it.isNotBlank() } ?: run {
            android.util.Log.i("YY-SPEED", "B站兜底：失败 无 bvid")
            return false
        }
        android.util.Log.i("YY-SPEED", "B站兜底：解析直链 bvid=$bvid")
        val info = runCatching { BiliClient().resolvePlayUrl(bvid, 5_000L) }.getOrNull() ?: return false
        android.util.Log.i("YY-SPEED", "B站兜底：直链成功 len=${info.audioUrl.length}")
        _nowPlaying.value = _nowPlaying.value.copy(switching = true)
        val epoch = PlayerController.playUrl(
            info.audioUrl,
            music.songName,
            music.artistName,
            null,
        )
        if (startPosition > 0) PlayerController.seekTo(startPosition)
        if (!PlaybackStartWaiter.wait(epoch, 2_500L)) return false
        android.util.Log.i("YY-SPEED", "B站兜底：起播成功")
        _nowPlaying.value = _nowPlaying.value.copy(
            linksSummary = "B站音轨兜底",
            switching = false,
            exhausted = false,
            linkState = "在线",
            mvAudioUrl = info.audioUrl,
        )
        scope.launch {
            mvSourceStore.writeCacheMeta(
                mv,
                listOf(music.songName, music.artistName).filter { it.isNotBlank() }.joinToString(" - "),
            )
            VideoStore.download(info.audioUrl, VideoStore.audioPath(context, mvSourceStore.cacheKey(mv)))
        }
        return true
    }

    companion object {
        /** 起播等待上限（毫秒）：切歌 3~5s 目标；超时后按「是否已有缓冲」判定慢或死） */
        const val AWAIT_START_TIMEOUT_MS = 2_500L
        const val AWAIT_POLL_MS = 50L
        /** 竞速预算：抢跑窗口，抢不到就让慢轮接手（压缩后慢轮有预算跑满 2-3 轮） */
        const val FAST_RACE_BUDGET_MS = 1_600L
        const val SLOW_RESOLVE_BUDGET_MS = 6_000L
        const val SLOW_AWAIT_START_MS = 6_000L

        /**
         * 单次点歌/切歌的全局预算（硬上限）：fast 竞速 + 慢速补试 + 起播等待合计不超过它。
         * 这是「3~5s 起播」的兑现口径 —— 超出即降级（进兜底/终态），而不是继续叠加白等。
         * 正常路径（预热/锁定命中）在 3s 内返回，看不到这个上限。
         */
        const val TOTAL_BOOT_BUDGET_MS = 4_500L

        /** 竞速最多占用的时长：给慢轮与起播留出剩余预算（冷启动首播也落在 5s 内） */
        const val FAST_RACE_MAX_MS = 2_000L

        /** 起播等待的保底时长：剩余预算低于它就不值得再起播，直接判失败换下一条 */
        const val MIN_BOOT_AWAIT_MS = 800L

        /** 慢速补试的保底预算：探测本身并行、单轮 1.2s 内可完成，800ms 足够开一轮 */
        const val MIN_SLOW_BUDGET_MS = 800L

        /** 兜底门禁：autoSwitch 开启时至少跑过 N 轮慢轮才允许进 B站音轨兜底（跳切不轻易跨界） */
        const val MIN_FALLBACK_ROUNDS = 2

        const val MAX_REMOTE_ATTEMPTS = 8
        const val MAX_COVER_ATTEMPTS = 3
        const val MAX_EXPIRED_RETRY = 3

        /** 距全局预算截止还剩多久（可为负，调用方据此判断"还值不值得开新一步"） */
        fun remainingBootMs(deadlineAt: Long): Long = deadlineAt - System.currentTimeMillis()

        /** 取「剩余预算」与「本步理想预算」的较小值；剩余不足 [minMs] 时返回 null = 不再开新步骤 */
        fun budgetWithin(deadlineAt: Long, idealMs: Long, minMs: Long): Long? {
            val remaining = remainingBootMs(deadlineAt)
            if (remaining < minMs) return null
            return minOf(idealMs, remaining)
        }

        /** 在全局预算内等待起播：剩余不足 [minMs] 直接判失败，避免最后一条又吃满整段超时 */
        suspend fun awaitStartWithin(epoch: Int, deadlineAt: Long, idealMs: Long, minMs: Long): Boolean {
            val ms = budgetWithin(deadlineAt, idealMs, minMs) ?: return false
            return PlaybackStartWaiter.wait(epoch, ms)
        }

        @Volatile
        private var instance: PlaybackCoordinator? = null

        fun get(context: Context): PlaybackCoordinator =
            instance ?: synchronized(this) {
                instance ?: PlaybackCoordinator(context.applicationContext).also { instance = it }
            }
    }
}
