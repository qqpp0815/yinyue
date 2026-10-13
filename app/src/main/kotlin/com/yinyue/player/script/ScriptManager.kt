package com.yinyue.player.script

import android.content.Context
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.UserScriptEntity
import com.yinyue.script.InvalidLxScriptException
import com.yinyue.script.LxScriptEngine
import com.yinyue.script.LxScriptHeaderParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 洛雪脚本管理（Task 12）：导入/持久化/启停/优先级/连通性测试/热加载。
 * 每个脚本一个独立 QuickJS 引擎实例（崩溃隔离：脚本异常只影响自身）。
 */
class ScriptManager private constructor(private val db: AppDatabase, private val filesDir: java.io.File) {

    private val engines = ConcurrentHashMap<String, LxScriptEngine>()

    /** 脚本上报更新发生在沙箱回调线程，落库需要自己的作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每个脚本最近的 console 输出（有界），失败时用来告诉用户"脚本自己说了什么" */
    private val consoleTail = ConcurrentHashMap<String, ArrayDeque<String>>()

    fun lastConsoleMessage(id: String): String? = consoleTail[id]?.lastOrNull()

    private fun recordConsole(id: String, line: String) {
        val dq = consoleTail.getOrPut(id) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(line)
            while (dq.size > 5) dq.removeFirst()
        }
    }

    /** 导入脚本：解析头注释 → 查重 → 持久化 → 立即连通性测试 */
    suspend fun importScript(scriptText: String, sourceUrl: String = ""): UserScriptEntity = withContext(Dispatchers.IO) {
        val header = LxScriptHeaderParser.parse(scriptText) // 缺头注释抛 InvalidLxScriptException
        // 数量上限（对齐 lx：最多 20 个自定义源）
        val existing = db.userScriptDao().allOnce()
        if (existing.size >= MAX_SCRIPTS) {
            throw InvalidLxScriptException("自定义源数量已达上限（$MAX_SCRIPTS 个），请先删除部分")
        }
        // 重复内容检测（对齐 lx：gz_ 前缀比对）
        val digest = contentDigest(scriptText)
        if (existing.any { contentDigest(it.scriptText) == digest }) {
            val dup = existing.first { contentDigest(it.scriptText) == digest }
            throw InvalidLxScriptException("导入失败，脚本内容与已有的源「${dup.name}」相同")
        }
        val id = genScriptId()
        val entity = UserScriptEntity(
            id = id,
            name = header.name ?: id,
            description = header.description ?: "",
            author = header.author ?: "",
            homepage = header.homepage ?: "",
            version = header.version ?: "",
            scriptText = scriptText,
            enabled = true,
            priority = 0,
            createdAt = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
        )
        db.userScriptDao().insert(entity)
        refreshScriptsSnapshot()
        platformPriorityCache = null
        val ok = runCatching { connectTest(id) }.getOrDefault(false)
        if (ok) { loadFailedAt.remove(id) } else { loadFailedAt[id] = System.currentTimeMillis() }
        refreshScriptsSnapshot()
        platformPriorityCache = null
        entity.copy(lastTestOk = ok)
    }

    /** 在线导入：下载 URL（http/https，重定向 ≤3，大小 ≤9MB，对齐 lx 在线导入实现） */
    suspend fun importFromUrl(url: String): UserScriptEntity = withContext(Dispatchers.IO) {
        val body = downloadScript(url)
        importScript(body, sourceUrl = url)
    }

    /** 下载脚本文本（在线导入与检查更新共用同一套校验） */
    private fun downloadScript(url: String): String {
        if (!Regex("^https?://").containsMatchIn(url)) {
            throw InvalidLxScriptException("请输入 HTTP 链接")
        }
        val candidates = buildList {
            add(url)
            if (url.startsWith("https://raw.githubusercontent.com/")) {
                val path = url.removePrefix("https://raw.githubusercontent.com/")
                add("https://cdn.jsdelivr.net/gh/$path")
                add("https://ghproxy.net/https://raw.githubusercontent.com/$path")
            }
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            try {
                val body = client.newCall(
                    okhttp3.Request.Builder().url(candidate).header("User-Agent", "Mozilla/5.0").build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw InvalidLxScriptException("下载失败 HTTP ${resp.code}")
                    resp.body?.string() ?: throw InvalidLxScriptException("下载内容为空")
                }
                if (body.length > MAX_SCRIPT_BYTES) {
                    throw InvalidLxScriptException("脚本过大（>9MB），已拒绝导入")
                }
                return body
            } catch (e: InvalidLxScriptException) {
                throw e
            } catch (e: Exception) {
                lastError = e
        }
        }
        throw InvalidLxScriptException("下载失败：${lastError?.message}")
    }

    /**
     * 检查更新（需 sourceUrl；本地/剪贴板导入的脚本没有来源地址，只能手动重新导入）。
     * 判定以**内容**为准（脚本可能改了内容但忘了改 @version），版本号仅用于展示。
     */
    suspend fun checkUpdate(id: String): ScriptUpdateResult = withContext(Dispatchers.IO) {
        val row = db.userScriptDao().findById(id) ?: return@withContext ScriptUpdateResult.Failed("脚本不存在")
        // 优先用脚本上报的 updateUrl（落雪契约），其次在线导入的来源地址
        val alertUrl = runCatching {
            com.google.gson.JsonParser.parseString(row.reportedUpdateJson).asJsonObject
                .get("updateUrl")?.takeIf { !it.isJsonNull }?.asString ?: ""
        }.getOrDefault("")
        val url = alertUrl.takeIf { it.startsWith("http") } ?: row.sourceUrl
        if (url.isBlank()) {
            return@withContext ScriptUpdateResult.NoSourceUrl
        }
        val body = try {
            downloadScript(url)
        } catch (e: Exception) {
            return@withContext ScriptUpdateResult.Failed(e.message ?: "下载失败")
        }
        when (val d = com.yinyue.script.ScriptUpdateDecider.decide(row.scriptText, body, row.version)) {
            is com.yinyue.script.ScriptUpdateDecider.Decision.UpToDate ->
                ScriptUpdateResult.UpToDate(d.version)

            is com.yinyue.script.ScriptUpdateDecider.Decision.HasUpdate ->
                ScriptUpdateResult.HasUpdate(
                    oldVersion = row.version,
                    newVersion = d.newVersion,
                    newText = d.newText,
                )

            is com.yinyue.script.ScriptUpdateDecider.Decision.InvalidRemote ->
                ScriptUpdateResult.Failed("远端内容不是有效的洛雪脚本：${d.reason}")
        }
    }

    /**
     * 应用更新：查重（可能与其他源内容撞车）→ 替换 → 热加载 → 返回提示文案。
     * 由 UI 在用户确认后调用，不做静默替换。
     */
    suspend fun applyUpdate(id: String, newText: String): String = withContext(Dispatchers.IO) {
        val row = db.userScriptDao().findById(id) ?: return@withContext "脚本不存在"
        val header = try {
            LxScriptHeaderParser.parse(newText)
        } catch (e: Exception) {
            return@withContext "更新失败：远端内容不是有效的洛雪脚本"
        }
        val digest = contentDigest(newText)
        db.userScriptDao().allOnce()
            .filter { it.id != id }
            .firstOrNull { contentDigest(it.scriptText) == digest }
            ?.let { return@withContext "更新失败：内容与已有的源「${it.name}」相同" }

        engines.remove(id)?.close()
        db.userScriptDao().applyScriptUpdate(
            id = id,
            text = newText,
            name = header.name ?: row.name,
            description = header.description ?: "",
            author = header.author ?: "",
            homepage = header.homepage ?: "",
            version = header.version ?: "",
        )
        val ok = connectTest(id)
        val newName = header.name ?: row.name
        val verText = (header.version ?: "").ifBlank { "未标注版本" }
        "已更新「$newName」到 $verText，连通性测试${if (ok) "通过" else "失败"}"
    }

    suspend fun setAllowUpdateAlert(id: String, allow: Boolean) {
        db.userScriptDao().setAllowUpdateAlert(id, allow)
    }

    suspend fun allScriptsOnce(): List<UserScriptEntity> = db.userScriptDao().allOnce()

    /**
     * 批量探测专用导入：候选脚本已完成真实取链验证，跳过重复连通性测试，
     * 只负责去重、持久化和按健康度排序，避免 30 次串行 healthcheck 拖慢导入。
     */
    suspend fun importScriptForProbe(
        scriptText: String,
        sourceUrl: String,
        healthPriority: Int,
    ): UserScriptEntity = withContext(Dispatchers.IO) {
        val header = LxScriptHeaderParser.parse(scriptText)
        val existing = db.userScriptDao().allOnce()
        val digest = contentDigest(scriptText)
        if (existing.size >= MAX_SCRIPTS) {
            throw InvalidLxScriptException("自定义源数量已达上限（$MAX_SCRIPTS 个），请先删除部分")
        }
        if (existing.any { contentDigest(it.scriptText) == digest }) {
            val duplicate = existing.first { contentDigest(it.scriptText) == digest }
            return@withContext duplicate
        }
        val entity = UserScriptEntity(
            id = genScriptId(),
            name = header.name ?: "批量验证源",
            description = header.description ?: "",
            author = header.author ?: "",
            homepage = header.homepage ?: "",
            version = header.version ?: "",
            scriptText = scriptText,
            enabled = true,
            priority = (Int.MAX_VALUE - healthPriority).coerceAtLeast(0),
            createdAt = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
        )
        db.userScriptDao().insert(entity)
        refreshScriptsSnapshot()
        platformPriorityCache = null
        db.userScriptDao().updateTestResult(entity.id, System.currentTimeMillis(), true)
        entity
    }

    /** 忽略脚本上报的更新提醒 */
    suspend fun dismissReportedUpdate(id: String) {
        db.userScriptDao().setReportedUpdate(id, "")
    }

    private fun contentDigest(script: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(script.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** 连通性测试：完整加载脚本并等待 inited（v3.0 healthcheck 约定） */
    suspend fun connectTest(id: String): Boolean {
        val row = db.userScriptDao().findById(id) ?: return false
        val ok = try {
            val engine = freshEngine(row)
            coroutineScope {
                withTimeout(12_000) {
                    engine.load(row.scriptText, toScriptInfo(row))
                }
            }
            engines[id]?.close()
            engines[id] = engine
            true
        } catch (e: Exception) {
            engines.remove(id)?.close()
            false
        }.also { ok ->
            db.userScriptDao().updateTestResult(id, System.currentTimeMillis(), ok)
        }
        return ok
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        db.userScriptDao().setEnabled(id, enabled)
        if (!enabled) {
            engines.remove(id)?.close()
        } else {
            // 启用时懒加载（首次取链时 ensureEngine）；后台预热会顺带把引擎加载好
            refreshScriptsSnapshot()
        }
        platformPriorityCache = null
    }

    suspend fun setPriority(id: String, priority: Int) {
        db.userScriptDao().setPriority(id, priority)
        refreshScriptsSnapshot()
        platformPriorityCache = null
    }

    /**
     * 上移/下移脚本优先级（文档要求"优先级"可调）。
     * 取链时按优先级降序依次尝试，谁先成功用谁——并列时退化为"最后导入的最先试"，
     * 所以这里把整张表按当前可见顺序重排成显式序号 0..n-1，消除并列。
     */
    suspend fun movePriority(id: String, up: Boolean) {
        val list = db.userScriptDao().allOnce()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = if (up) idx - 1 else idx + 1
        if (target < 0 || target >= list.size) return
        val reordered = list.toMutableList().apply { add(target, removeAt(idx)) }
        reordered.forEachIndexed { i, s ->
            db.userScriptDao().setPriority(s.id, reordered.size - i)
        }
        refreshScriptsSnapshot()
        platformPriorityCache = null // 顺序变了：下次起播/切源按新优先级排序
    }

    /** 热加载：重建沙箱并替换实例 */
    suspend fun hotReload(id: String): Boolean {
        engines.remove(id)?.close()
        return connectTest(id)
    }

    suspend fun remove(id: String) {
        engines.remove(id)?.close()
        db.userScriptDao().delete(id)
        refreshScriptsSnapshot()
        platformPriorityCache = null
    }

    suspend fun get(id: String): UserScriptEntity? = db.userScriptDao().findById(id)

    /**
     * 取已加载引擎（无则懒加载；禁用/加载失败的脚本返回 null）。
     * 加载失败的脚本进入冷却期：自禁用/损坏的脚本每次都要等满 12s 超时，若不加冷却，
     * 每次起播/切源/切歌都会被它拖住（用户反馈的"切源卡住"里也含这一项）。
     */
    /**
     * 进行中的脚本加载（**单飞**）。
     *
     * 为什么必须单飞：冷启动时一次点歌会并发解析多条候选链接，每条都调 ensureEngine。
     * 没有单飞时每次调用都会**各自新建一次加载**（内部 12s 超时），于是一次起播变成 N × 12s，
     * 3~5s 切歌目标直接被拖爆。现在并发调用共享同一次加载结果。
     */
    private val loading = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<LxScriptEngine?>>()

    suspend fun ensureEngine(id: String): LxScriptEngine? {
        engines[id]?.let { return it }
        loadFailedAt[id]?.let { at ->
            if (System.currentTimeMillis() - at < LOAD_FAIL_COOLDOWN_MS) return null
            loadFailedAt.remove(id)
        }
        val deferred = loading.computeIfAbsent(id) {
            kotlinx.coroutines.CompletableDeferred<LxScriptEngine?>().also { d ->
                // 加载挂在 manager 自己的作用域：调用方被取消（如快速切歌打断）也不会中断加载，
                // 加载完成后引擎仍可被后续请求复用。
                scope.launch {
                    val result = runCatching { loadEngineNow(id) }.getOrNull()
                    loading.remove(id)
                    d.complete(result)
                }
            }
        }
        return deferred.await()
    }

    /** 真正的加载实现（单飞已在外层去重） */
    private suspend fun loadEngineNow(id: String): LxScriptEngine? {
        val row = db.userScriptDao().findById(id) ?: return null
        if (!row.enabled) return null
        return try {
            val engine = freshEngine(row)
            withTimeout(12_000) { engine.load(row.scriptText, toScriptInfo(row)) }
            engines[id] = engine
            loadFailedAt.remove(id)
            engine
        } catch (e: Exception) {
            db.userScriptDao().updateTestResult(id, System.currentTimeMillis(), false)
            loadFailedAt[id] = System.currentTimeMillis()
            null
        }
    }

    /**
     * 并行加载多个脚本引擎，整体不超过 [budgetMs]（取链热路径专用）。
     *
     * 为什么要并行 + 限时：单个脚本加载失败要走 12s 超时，串行调用会把"冷启动第一次点歌"
     * 拖成"脚本数 × 12s"；这里并行发起并整体限时，最坏也只用掉 [budgetMs]。
     * 已加载的直接复用，不做任何网络/求值。
     */
    suspend fun ensureEngines(ids: List<String>, budgetMs: Long): Map<String, LxScriptEngine> {
        val ready = ids.mapNotNull { id -> engines[id]?.let { id to it } }.toMap()
        val missing = ids.filterNot { ready.containsKey(it) }
        if (missing.isEmpty()) return ready
        val loaded = ConcurrentHashMap<String, LxScriptEngine>()
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                missing.forEach { id ->
                    launch {
                        runCatching { ensureEngine(id) }.getOrNull()?.let { loaded[id] = it }
                    }
                }
            }
        }
        return ready + loaded
    }

    /** 脚本 → 上次加载失败时间（冷却期内不再重试） */
    private val loadFailedAt = ConcurrentHashMap<String, Long>()

    fun loadedEngine(id: String): LxScriptEngine? = engines[id]

    fun loadedEngineIds(): Set<String> = engines.keys.toSet()

    /** 按优先级降序返回已启用脚本 */
    suspend fun enabledScripts(): List<UserScriptEntity> = db.userScriptDao().enabledScripts()

    /**
     * 平台 → 脚本优先级（取声明该平台的脚本里最高优先级）。
     * 起播/切源的链接顺序以此为主键：用户把某个脚本上移，它支持的平台就优先被使用。
     *
     * **只读已加载的引擎**：这是播放热路径（每次起播/切源都会调用），绝不能在这里触发脚本加载——
     * 自禁用/损坏的脚本要等 12s 超时，会把起播拖死（"切不过去"）。未加载的脚本跳过，
     * 由后台预热（SourcePreflight）负责先把引擎加载好，这里随后就能读到。
     */
    fun platformPriorities(): Map<String, Int> {
        val scripts = lastKnownScripts()
        val cached = platformPriorityCache
        if (cached != null && cached.scripts == scripts.map { it.id to it.priority }) return cached.map
        val map = HashMap<String, Int>()
        for (s in scripts) {
            val sources = loadedEngine(s.id)?.initedPayload?.sources?.keys ?: continue
            for (p in sources) map[p] = maxOf(map[p] ?: Int.MIN_VALUE, s.priority)
        }
        platformPriorityCache = PlatformPriorityCache(scripts.map { it.id to it.priority }, map)
        return map
    }

    /** 已启用脚本的非阻塞快照（供热路径使用；首次由后台预热刷新） */
    @Volatile
    private var scriptsSnapshot: List<UserScriptEntity> = emptyList()

    private fun lastKnownScripts(): List<UserScriptEntity> = scriptsSnapshot

    /** 后台刷新脚本快照（供 platformPriorities 使用） */
    suspend fun refreshScriptsSnapshot() {
        scriptsSnapshot = enabledScripts()
    }

    private data class PlatformPriorityCache(val scripts: List<Pair<String, Int>>, val map: Map<String, Int>)

    @Volatile
    private var platformPriorityCache: PlatformPriorityCache? = null

    private fun freshEngine(row: UserScriptEntity): LxScriptEngine =
        LxScriptEngine(
            // 脚本侧请求超时。为什么是 4s 而不是 8s：大量洛雪脚本在**初始化阶段**会联网做"检查更新"，
            // 8s 超时会把"引擎就绪"时间拖到十几秒 → 冷启动第一次点歌要等引擎（模拟器实测 6.5s+）。
            // 这些脚本在检查失败后都会继续正常初始化（日志可见"检查更新失败: timeout → 正常初始化"），
            // 所以快速失败是安全的；**取链请求不受影响**（MusicUrlResolver 里显式传 5s）。
            defaultRequestTimeoutMs = 4_000,
            initTimeoutMs = 10_000,
            // 脚本 console 落到 logcat（tag=LxScript），并留最近几条用于失败原因提示
            consoleSink = { level, text ->
                android.util.Log.i("LxScript", "[${row.name}] $level: ${text.take(500)}")
                if (level == "error" || level == "warn" || level == "log") {
                    recordConsole(row.id, text.take(300))
                }
            },
            // 脚本上报更新：落库到卡片（是否展示由 allowShowUpdateAlert 决定）
            updateAlertSink = { json ->
                if (row.allowShowUpdateAlert) {
                    android.util.Log.i("LxScript", "[${row.name}] 上报更新: ${json.take(300)}")
                    scope.launch { db.userScriptDao().setReportedUpdate(row.id, json) }
                }
            },
            // v3.0 §3.2：localStorage 按脚本落盘（跨重启保留，且天然只对该脚本可见）
            storage = FileLxStorage(java.io.File(java.io.File(filesDir, "script_storage"), "${row.id}.json")),
        )

    private fun toScriptInfo(row: UserScriptEntity) = com.yinyue.script.LxCurrentScriptInfo(
        name = row.name,
        description = row.description,
        version = row.version,
        author = row.author,
        homepage = row.homepage,
        env = "mobile",
        rawScript = row.scriptText,
    )

    private fun genScriptId(): String {
        val rnd = SecureRandom().nextInt(900) + 100
        return "user_api_${rnd}_${System.currentTimeMillis()}"
    }

    /** 检查更新结果 */
    sealed interface ScriptUpdateResult {
        /** 远端内容与本地一致 */
        data class UpToDate(val version: String) : ScriptUpdateResult

        /** 远端内容有变化（version 可能为空——脚本改了内容没改版本号） */
        data class HasUpdate(val oldVersion: String, val newVersion: String, val newText: String) : ScriptUpdateResult

        /** 本地/剪贴板导入的脚本没有来源地址 */
        data object NoSourceUrl : ScriptUpdateResult

        data class Failed(val message: String) : ScriptUpdateResult
    }

    /**
     * 脚本 localStorage 的文件实现（v3.0 §3.2）：按脚本 id 落一个 JSON 文件。
     * 每个脚本一个实例 → 天然隔离；写操作即时落盘（脚本量小、频率低，开销可忽略）。
     */
    private class FileLxStorage(private val file: java.io.File) : com.yinyue.script.LxStorage {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val gson = com.google.gson.Gson()

        init {
            runCatching {
                if (file.exists()) {
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                    val loaded: Map<String, String>? = gson.fromJson(file.readText(), type)
                    loaded?.let { map.putAll(it) }
                }
            }
        }

        private fun persist() {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(map))
            }
        }

        override fun get(key: String): String? = map[key]

        override fun set(key: String, value: String) {
            map[key] = value
            persist()
        }

        override fun remove(key: String) {
            map.remove(key)
            persist()
        }

        override fun clear() {
            map.clear()
            persist()
        }

        override fun keys(): List<String> = map.keys.toList()
    }
    companion object {
        private const val MAX_SCRIPTS = 50
        private const val MAX_SCRIPT_BYTES = 9_000_000 // 对齐 lx 在线导入大小上限

        /** 加载失败后的冷却时间：冷却期内不再尝试加载该脚本（避免每次操作都等 12s 超时） */
        private const val LOAD_FAIL_COOLDOWN_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: ScriptManager? = null

        fun get(context: Context): ScriptManager =
            instance ?: synchronized(this) {
                val app = context.applicationContext
                instance ?: ScriptManager(AppDatabase.get(app), app.filesDir).also { instance = it }
            }
    }
}
