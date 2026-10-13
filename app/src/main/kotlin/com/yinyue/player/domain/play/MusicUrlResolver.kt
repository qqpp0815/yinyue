package com.yinyue.player.domain.play

import android.content.Context
import android.util.Log
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.prefs.SettingsRepository
import com.yinyue.player.data.source.SourceRegistry
import com.yinyue.player.data.source.model.QualityType
import com.yinyue.player.data.source.model.RawTrack
import com.yinyue.player.data.db.entity.UserScriptEntity
import com.yinyue.player.script.ScriptManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MusicUrlResolver private constructor(private val context: Context) {
    private val db = AppDatabase.get(context)

    data class Resolved(
        val url: String,
        val scriptId: String,
        val scriptPriority: Int,
        val responseTimeMs: Long,
        val quality: QualityType,
    )

    data class Combo(
        val url: String,
        val scriptId: String,
        val scriptPriority: Int,
        val quality: QualityType,
        val atMs: Long,
    )

    data class SlowRace(
        val winners: List<Pair<AudioLink, Resolved>>,
        val finished: Set<Long>,
    )

    suspend fun resolve(link: AudioLink, music: MusicEntity? = null): Resolved? {
        comboCache[link.id]?.let { combo ->
            if (System.currentTimeMillis() - combo.atMs < COMBO_TTL_MS) {
                return Resolved(combo.url, combo.scriptId, combo.scriptPriority, 0L, combo.quality)
            }
            comboCache.remove(link.id)
        }
        if (hasFreshLock(link)) {
            val quality = QualityType.fromLx(link.resolvedQuality.orEmpty())
            return Resolved(
                url = link.resolvedUrl.orEmpty(),
                scriptId = link.resolvedScriptId.orEmpty(),
                scriptPriority = 0,
                responseTimeMs = 0L,
                quality = quality,
            )
        }
        val startedAt = System.currentTimeMillis()
        val preferred = SettingsRepository.get(context).preferredQuality()
        val advertised = rawCache[link.platform]?.get(link.remoteTrackId)
            ?.qualitys?.map { it.type.lxName }?.toSet().orEmpty()
        val chain = com.yinyue.domain.match.QualityPreference.chain(preferred)
        val filtered = if (advertised.isEmpty()) chain else chain.filter { it in advertised }
        val candidates = (filtered.ifEmpty { listOf(chain.last()) })
            .let { if (it.size <= 2) it else listOf(it.first(), it.last()) }
            .map(QualityType::fromLx)
        val scriptManager = ScriptManager.get(context)
        val scripts = scriptManager.enabledScripts()
            val musicInfoJson = buildMusicInfoJsonForScripts(link, music, scripts)
        var engines = scriptManager.ensureEngines(
            scripts.map { it.id },
            budgetMs = if (scriptManager.loadedEngineIds().isEmpty()) ENGINE_LOAD_BUDGET_MS else 0L,
        )
        if (engines.isEmpty() && scripts.isNotEmpty()) {
            engines = scriptManager.ensureEngines(
                scripts.map { it.id },
                budgetMs = ENGINE_LOAD_BUDGET_MS,
            )
        }
        val deadline = System.currentTimeMillis() + RESOLVE_OVERALL_BUDGET_MS
        var attempted = false
        var timedOut = false

        for (quality in candidates) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                timedOut = true
                break
            }
            val usable = scripts.mapNotNull { script ->
                val engine = engines[script.id] ?: return@mapNotNull null
                val source = engine.initedPayload?.sources?.get(link.platform) ?: return@mapNotNull null
                if ("musicUrl" !in source.actions) return@mapNotNull null
                if (source.qualitys.isNotEmpty() && quality.lxName !in source.qualitys) return@mapNotNull null
                Triple(script, engine, source)
            }
            if (usable.isEmpty()) continue
            attempted = true
            val winner = withTimeoutOrNull(minOf(RESOLVE_OVERALL_MS, remaining)) {
                coroutineScope {
                    val resultChannel = kotlinx.coroutines.channels.Channel<Resolved?>(Channel.UNLIMITED)
                    val deferreds = usable.map { (script, engine, _) ->
                        async {
                            val callStartedAt = System.currentTimeMillis()
                            val url = runCatching {
                                engine.resolveMusicUrl(
                                    source = link.platform,
                                    quality = quality.lxName,
                                    musicInfoJson = musicInfoJson,
                                    timeoutMs = 5_000,
                                )
                            }.onFailure {
                                Log.i("YY-SPEED", "resolve ${link.platform}@${script.name}: error ${it.message}")
                            }.getOrNull()
                            val result = url?.takeIf(::isPlausibleUrl)?.let {
                                Resolved(
                                    it,
                                    script.id,
                                    script.priority,
                                    System.currentTimeMillis() - callStartedAt,
                                    quality,
                                )
                            }
                            resultChannel.trySend(result)
                            result
                        }
                    }
                    var firstWinner: Resolved? = null
                    repeat(deferreds.size) {
                        if (firstWinner != null) return@repeat
                        firstWinner = resultChannel.receiveCatching().getOrNull() ?: firstWinner
                    }
                    coroutineContext.cancelChildren()
                    firstWinner
                }
            }
            if (winner != null) {
                SourceHealth.markPlayable(context, link)
                SourceHealth.lockResolved(
                    context,
                    link.id,
                    winner.url,
                    winner.quality.lxName,
                    winner.scriptId,
                )
                comboCache[link.id] = Combo(
                    winner.url,
                    winner.scriptId,
                    winner.scriptPriority,
                    winner.quality,
                    System.currentTimeMillis(),
                )
                Log.i("YY-SPEED", "resolve ${link.platform}: success ${System.currentTimeMillis() - startedAt}ms")
                return winner
            }
        }
        Log.i(
            "YY-SPEED",
            "resolve ${link.platform}: failed ${System.currentTimeMillis() - startedAt}ms attempted=$attempted timedOut=$timedOut",
        )
        if (attempted && !timedOut) {
            SourceHealth.markExpired(context, link.id)
        }
        return null
    }
    suspend fun resolveFirstPlayable(
        links: List<AudioLink>,
        music: MusicEntity?,
        batchSize: Int = 8,
        budgetMs: Long = RESOLVE_RACE_BUDGET_MS,
    ): Pair<AudioLink, Resolved>? {
        if (links.isEmpty()) return null
        val batch = links.take(batchSize)
        val raceId = raceCounter.incrementAndGet()
        val raceStartedAt = System.currentTimeMillis()
        var winner: Pair<AudioLink, Resolved>? = null
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                val alive = Channel<Pair<AudioLink, Resolved>>(Channel.UNLIMITED)
                val pendingCount = AtomicInteger(batch.size)
                val finishOne = {
                    if (pendingCount.decrementAndGet() <= 0) {
                        runCatching { alive.close() }
                    }
                }
                batch.forEach { link ->
                    launch {
                        val resolved = runCatching { resolve(link, music) }.getOrNull()
                        if (resolved == null) {
                            finishOne()
                            return@launch
                        }
                        val probeStartedAt = System.currentTimeMillis()
                        val ok = probeAlive(resolved.url)
                        val probeCost = System.currentTimeMillis() - probeStartedAt
                        if (ok || probeCost >= PROBE_WAIT_MS) {
                            alive.trySend(link to resolved)
                        } else if (hasFreshLock(link)) {
                            runCatching { SourceHealth.clearLock(context, link.id) }
                        }
                        finishOne()
                    }
                }
                winner = alive.receiveCatching().getOrNull()
                coroutineContext.cancelChildren()
            }
        }
        Log.i("YY-PROBE", "race#$raceId ${System.currentTimeMillis() - raceStartedAt}ms winner=${winner?.first?.platform ?: "none"}")
        return winner
    }

    suspend fun resolveAllPlayable(
        links: List<AudioLink>,
        music: MusicEntity?,
        budgetMs: Long,
    ): SlowRace {
        if (links.isEmpty()) return SlowRace(emptyList(), emptySet())
        val raceId = raceCounter.incrementAndGet()
        val raceStartedAt = System.currentTimeMillis()
        val winners = java.util.Collections.synchronizedList(ArrayList<Pair<AudioLink, Resolved>>())
        val finished = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                links.map { link ->
                    async {
                        try {
                            val resolved = runCatching { resolve(link, music) }.getOrNull() ?: return@async
                            val ok = probeAlive(resolved.url)
                            if (ok) {
                                winners.add(link to resolved)
                            } else if (hasFreshLock(link)) {
                                runCatching { SourceHealth.clearLock(context, link.id) }
                            }
                        } finally {
                            finished.add(link.id)
                        }
                    }
                }.awaitAll()
            }
        }
        Log.i("YY-PROBE", "slowrace#$raceId winners=${winners.size}/${links.size}")
        return SlowRace(winners.toList(), finished)
    }

    suspend fun prewarm(link: AudioLink, music: MusicEntity?) {
        val cached = comboCache[link.id]
        if (cached != null && System.currentTimeMillis() - cached.atMs < COMBO_TTL_MS) return
        runCatching { resolve(link, music) }
    }
    private val musicInfoLookup = ConcurrentHashMap<String, String>()

    private suspend fun buildMusicInfoJsonForScripts(
        link: AudioLink,
        music: MusicEntity?,
        scripts: List<UserScriptEntity>,
    ): String {
        link.musicInfoJson?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = rawCache[link.platform]?.get(link.remoteTrackId)
        if (raw != null) {
            val version = scripts.firstOrNull { it.id == link.resolvedScriptId }?.version
                ?: scripts.firstOrNull()?.version
                ?: ""
            return raw.toOldMusicInfoJson(version).toString()
        }
        if (music == null) return "{}"
        val lookupKey = "${link.platform}|${music.songName}|${music.artistName}|${link.remoteTrackId}"
        musicInfoLookup[lookupKey]?.let { return it }
        val provider = SourceRegistry.get(link.platform) ?: return "{}"
        val json = withTimeoutOrNull(MUSIC_INFO_BUDGET_MS) {
            try {
                val keyword = listOf(music.songName, music.artistName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                val page = provider.search(keyword, 1, 10)
                val match = page.list.firstOrNull { it.remoteTrackId == link.remoteTrackId }
                    ?: page.list.firstOrNull()
                match?.let {
                    cacheRaw(it)
                    it.toOldMusicInfoJson("").toString()
                }
            } catch (_: Exception) {
                null
            }
        }
        if (json.isNullOrBlank() || json == "{}") return "{}"
        musicInfoLookup[lookupKey] = json
        runCatching { db.audioLinkDao().updateMusicInfo(link.id, json) }
        return json
    }

    companion object {
        private const val COMBO_TTL_MS = 30L * 60 * 1000
        const val LOCK_TTL_MS = 30L * 60 * 1000
        private const val RESOLVE_OVERALL_MS = 3_500L
        private const val RESOLVE_OVERALL_BUDGET_MS = 5_000L
        private const val ENGINE_LOAD_BUDGET_MS = 3_500L
        const val PROBE_WAIT_MS = 800
        const val RESOLVE_RACE_BUDGET_MS = 3_500L
        private const val MUSIC_INFO_BUDGET_MS = 3_000L

        private val raceCounter = AtomicInteger(0)
        private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val comboCache = ConcurrentHashMap<Long, Combo>()
        val rawCache = ConcurrentHashMap<String, MutableMap<String, RawTrack>>()

        @Volatile
        private var instance: MusicUrlResolver? = null

        private val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(900, TimeUnit.MILLISECONDS)
                .readTimeout(1_000, TimeUnit.MILLISECONDS)
                .callTimeout(1_200, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()
        }

        fun isPlausibleUrl(url: String): Boolean {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            return !url.contains("undefined") && !url.contains("null") && !url.contains("NaN")
        }

        fun hasFreshLock(link: AudioLink): Boolean {
            val resolvedAt = link.resolvedAt ?: 0L
            return !link.resolvedUrl.isNullOrBlank() && resolvedAt > 0 &&
                System.currentTimeMillis() - resolvedAt < LOCK_TTL_MS &&
                isPlausibleUrl(link.resolvedUrl.orEmpty())
        }

        fun clearComboCache() = comboCache.clear()

        fun invalidateCombo(linkId: Long) {
            comboCache.remove(linkId)
        }

        fun cachedCombo(linkId: Long): Combo? = comboCache[linkId]?.takeIf {
            System.currentTimeMillis() - it.atMs < COMBO_TTL_MS
        }

        fun cacheRaw(track: RawTrack) {
            rawCache.getOrPut(track.platform) { ConcurrentHashMap() }[track.remoteTrackId] = track
        }

        fun get(context: Context): MusicUrlResolver = instance ?: synchronized(this) {
            instance ?: MusicUrlResolver(context.applicationContext).also { instance = it }
        }

        suspend fun probeAlive(url: String): Boolean {
            val done = CompletableDeferred<Boolean>()
            probeScope.launch {
                done.complete(runCatching { probeUrls(listOf(url)).isNotEmpty() }.getOrDefault(false))
            }
            return withTimeoutOrNull(PROBE_WAIT_MS.toLong()) { done.await() } == true
        }

        suspend fun probeUrls(urls: List<String>): Set<String> {
            if (urls.isEmpty()) return emptySet()
            return withContext(Dispatchers.IO) {
                coroutineScope {
                    urls.map { url ->
                        async {
                            val ok = runCatching {
                                val request = Request.Builder()
                                    .url(url)
                                    .header("Range", "bytes=0-1023")
                                    .header("User-Agent", "Mozilla/5.0")
                                    .get()
                                    .build()
                                probeClient.newCall(request).execute().use { response ->
                                    if (response.code != 206 && response.code != 200) return@use false
                                    val stream = response.body?.byteStream() ?: return@use false
                                    stream.read() >= 0
                                }
                            }.getOrDefault(false)
                            url to ok
                        }
                    }.awaitAll().filter { it.second }.map { it.first }.toSet()
                }
            }
        }
    }
}
