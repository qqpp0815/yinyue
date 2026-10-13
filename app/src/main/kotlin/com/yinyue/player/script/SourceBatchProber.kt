package com.yinyue.player.script

import android.content.Context
import android.util.Log
import com.yinyue.player.data.source.SourceRegistry
import com.yinyue.player.domain.play.MusicUrlResolver
import com.yinyue.script.LxScriptHeaderParser
import com.yinyue.script.LxCurrentScriptInfo
import com.yinyue.script.LxScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SourceProbeProgress(
    val phase: SourceProbePhase = SourceProbePhase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val imported: Int = 0,
    val healthy: Int = 0,
    val failureSummary: String = "",
)

enum class SourceProbePhase { IDLE, FETCHING, PROBING, IMPORTING, DONE, FAILED }

data class SourceProbeResult(
    val candidates: Int,
    val healthy: Int,
    val imported: Int,
    val failed: Int,
)

object SourceBatchProber {
    private const val MAX_CANDIDATES = 300
    private const val MAX_IMPORTS = 30
    private const val PROBE_CONCURRENCY = 4
    private const val ENGINE_TIMEOUT_MS = 8_000L

    private val repositories = listOf(
        "Lingeng27/lx-music-1010-source" to "main",
        "Macrohard0001/lx-ikun-music-sources" to "main",
        "morenb3396/lx-music-sources" to "main",
        "fengs2021/lx-music-merged-source" to "main",
        "shiliu6924/lx-music-source" to "main",
    )

    private val progress = MutableStateFlow(SourceProbeProgress())
    val progressState: StateFlow<SourceProbeProgress> = progress

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(PROBE_CONCURRENCY)

    suspend fun probeAndImport(context: Context): SourceProbeResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val manager = ScriptManager.get(appContext)
        val existing = manager.allScriptsOnce()
        val existingDigests = existing.map { it.scriptText.hashCode() }.toHashSet()
        val importedUrls = existing.mapNotNull { it.sourceUrl }.toHashSet()

        progress.value = SourceProbeProgress(phase = SourceProbePhase.FETCHING)
        val candidates = try {
            loadCandidates()
        } catch (e: Exception) {
            progress.value = SourceProbeProgress(
                phase = SourceProbePhase.FAILED,
                current = e.message ?: "候选源拉取失败",
            )
            return@withContext SourceProbeResult(0, 0, 0, 0)
        }

        Log.w("SourceBatch", "probe start candidates=${candidates.size}")
        val unique = candidates
            .filter { it.url !in importedUrls }
            .distinctBy { it.url }
            .take(MAX_CANDIDATES)
        progress.value = progress.value.copy(total = unique.size)

        val platformInfos = loadPlatformInfos()
        Log.w("SourceBatch", "platform metadata=${platformInfos.map { it.platform }}")
        if (platformInfos.isEmpty()) {
            progress.value = SourceProbeProgress(
                phase = SourceProbePhase.FAILED,
                current = "元数据搜索失败，无法验证脚本",
            )
            return@withContext SourceProbeResult(unique.size, 0, 0, 0)
        }

        val healthy = ArrayList<CandidateScript>()
        val probed = AtomicInteger()
        var failures = 0
        val failureReasons = java.util.Collections.synchronizedMap(HashMap<String, Int>())
        coroutineScope {
            unique.map { candidate ->
                async {
                    semaphore.withPermit {
                        val passed = runCatching {
                            probeScript(candidate, platformInfos, failureReasons)
                        }.onFailure {
                            Log.w("SourceBatch", "probe failed ${candidate.name}: ${it.message}")
                            failureReasons.merge(
                                (it.message ?: it.javaClass.simpleName).take(100),
                                1,
                                Int::plus,
                            )
                        }.getOrDefault(ProbeOutcome("", emptyList()))
                        synchronized(healthy) {
                            if (passed.platforms.isEmpty()) {
                                failures++
                            } else {
                                healthy += candidate.copy(
                                    scriptText = passed.scriptText,
                                    platforms = passed.platforms,
                                )
                            }
                        }
                        progress.value = progress.value.copy(
                            phase = SourceProbePhase.PROBING,
                            done = probed.incrementAndGet(),
                            total = unique.size,
                            current = candidate.name,
                            healthy = healthy.size,
                        )
                    }
                }
            }.awaitAll()
        }

        val ranked = healthy
            .sortedWith(
                compareByDescending<CandidateScript> { it.platforms.size }
                    .thenBy { it.platforms.minOf { probe -> probe.responseTimeMs } },
            )
            .take(MAX_IMPORTS)
            .mapIndexed { index, item -> item to index }
        progress.value = progress.value.copy(
            phase = SourceProbePhase.IMPORTING,
            done = 0,
            total = ranked.size,
        )

        var imported = 0
        for ((item, rank) in ranked) {
            val digest = item.scriptText.hashCode()
            if (digest in existingDigests) continue
            val entity = runCatching {
                manager.importScriptForProbe(item.scriptText, item.url, rank)
            }.getOrNull()
            if (entity != null) {
                imported++
                existingDigests += digest
                progress.value = progress.value.copy(
                    done = imported,
                    total = ranked.size,
                    imported = imported,
                    current = entity.name,
                )
            }
        }

        val result = SourceProbeResult(unique.size, healthy.size, imported, failures)
        progress.value = SourceProbeProgress(
            phase = SourceProbePhase.DONE,
            done = ranked.size,
            total = ranked.size,
            current = "可用 ${healthy.size}，导入 $imported",
            imported = imported,
            healthy = healthy.size,
            failureSummary = failureReasons.entries
                .sortedByDescending { it.value }
                .take(8)
                .joinToString("；") { "${it.key}×${it.value}" },
        )
        result
    }

    private suspend fun loadCandidates(): List<CandidateScript> = coroutineScope {
        repositories.map { (repo, branch) ->
            async {
                runCatching { treePaths(repo, branch) }.getOrElse { emptyList() }
                    .map { path ->
                        CandidateScript(
                            name = path.substringAfterLast('/'),
                            url = "https://raw.githubusercontent.com/$repo/$branch/${path.urlEncodedPath()}",
                        )
                    }
            }
        }.awaitAll().flatten()
            .asSequence()
            .filter { it.name.endsWith(".js", ignoreCase = true) }
            .groupBy { it.name }
            .flatMap { (_, candidates) ->
                candidates.sortedByDescending { it.name }.take(2)
            }
            .toList()
            .shuffled()
            .take(MAX_CANDIDATES)
    }

    private suspend fun treePaths(repo: String, branch: String): List<String> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub ${response.code}" }
            val body = response.body?.string().orEmpty()
            val regex = Regex("\"path\"\\s*:\\s*\"([^\"]+\\.js)\"")
            return regex.findAll(body).map { it.groupValues[1] }.toList()
        }
    }

    private fun versionKey(path: String): String = path.substringAfterLast('/')

    private suspend fun loadPlatformInfos(): List<PlatformInfo> = coroutineScope {
        SourceRegistry.all().map { provider ->
            async {
                runCatching {
                    provider.search("梁静茹 勇气", 1, 3).list.firstOrNull()
                }.getOrNull()?.let { track ->
                    PlatformInfo(provider.platform, track.toOldMusicInfoJson("").toString())
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun probeScript(
        candidate: CandidateScript,
        infos: List<PlatformInfo>,
        failureReasons: MutableMap<String, Int>,
    ): ProbeOutcome {
        val text = downloadScript(candidate.url)
        if (text == null) {
            failureReasons.merge("download failed", 1, Int::plus)
            return ProbeOutcome("", emptyList())
        }
        Log.i("SourceBatch", "candidate loaded ${candidate.name}")
        val engine = LxScriptEngine(
            allowedHosts = emptySet(),
            defaultRequestTimeoutMs = ENGINE_TIMEOUT_MS,
            initTimeoutMs = ENGINE_TIMEOUT_MS,
        )
        return try {
            val payload = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
                engine.load(text, LxCurrentScriptInfo(env = "mobile"))
            } ?: run {
                failureReasons.merge("engine init timeout", 1, Int::plus)
                return ProbeOutcome(text, emptyList())
            }
            Log.i("SourceBatch", "engine inited ${candidate.name}: ${payload.sources.keys}")
            val supported = infos.filter { payload.sources.containsKey(it.platform) }
            if (supported.isEmpty()) {
                Log.w("SourceBatch", "no supported platforms ${candidate.name}")
                failureReasons.merge("no supported platforms", 1, Int::plus)
                return ProbeOutcome(text, emptyList())
            }

            coroutineScope {
                supported.take(2).map { info ->
                    async {
                        val startedAt = System.currentTimeMillis()
                        val url = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
                            engine.resolveMusicUrl(
                                info.platform,
                                "128k",
                                info.musicInfoJson,
                                ENGINE_TIMEOUT_MS,
                            )
                        }
                        if (url == null) {
                            Log.w("SourceBatch", "resolve failed ${candidate.name}@${info.platform}")
                            failureReasons.merge("resolve failed", 1, Int::plus)
                            return@async null
                        }
                        if (MusicUrlResolver.probeAlive(url)) {
                            PlatformProbe(info.platform, System.currentTimeMillis() - startedAt)
                        } else {
                            Log.w("SourceBatch", "url probe failed ${candidate.name}@${info.platform}: $url")
                            failureReasons.merge("url probe failed", 1, Int::plus)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
                .let { ProbeOutcome(text, it) }
        } finally {
            engine.close()
        }
    }

    private fun downloadScript(url: String): String? {
        val candidates = mutableListOf(url)
        if (url.startsWith("https://raw.githubusercontent.com/")) {
            val path = url.removePrefix("https://raw.githubusercontent.com/")
            candidates += "https://gh-proxy.com/https://raw.githubusercontent.com/$path"
            candidates += "https://ghproxy.net/https://raw.githubusercontent.com/$path"
        }
        for (candidate in candidates) {
            val request = try {
                Request.Builder().url(candidate).build()
            } catch (_: Exception) {
                continue
            }
            val text = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string()?.takeIf { bodyText ->
                        bodyText.isNotBlank() && runCatching {
                            LxScriptHeaderParser.parse(bodyText)
                        }.isSuccess
                    }
                }
            }.getOrNull()
            if (text != null) return text
        }
        return null
    }
}

private data class CandidateScript(
    val name: String,
    val url: String,
    val scriptText: String = "",
    val platforms: List<PlatformProbe> = emptyList(),
)

private data class PlatformInfo(
    val platform: String,
    val musicInfoJson: String,
)

private data class PlatformProbe(
    val platform: String,
    val responseTimeMs: Long,
)

private data class ProbeOutcome(
    val scriptText: String,
    val platforms: List<PlatformProbe>,
)

private fun String.urlEncodedPath(): String =
    split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }
