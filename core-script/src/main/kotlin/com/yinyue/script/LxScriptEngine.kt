package com.yinyue.script

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Md5 {
    fun hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 洛雪自定义源脚本引擎（Spike 版）。
 *
 * 事件模型对齐 lx-music-desktop preload.js（API 2.0.0）：
 * 脚本只在加载时执行一次，通过 lx.send('inited', {sources}) 声明能力，
 * 之后长驻响应 lx.on('request', handler)，handler 返回 Promise<string>。
 *
 * 桥接方式：Kotlin 注册少量全局 native 函数（__lx_*），
 * 由注入的 JS shim 组装出 window.lx 完整 API；请求经 async IIFE + await
 * 回传结果（quickjs-kt 的 evaluate 支持顶层 await 并驱动 Promise 作业）。
 *
 * 超时：用协程 withTimeout 打断 evaluate（quickjs-kt 支持取消中断死循环）。
 */
class LxScriptEngine(
    /** 域名白名单；空集合 = 不限制 */
    private val allowedHosts: Set<String> = emptySet(),
    /** 单次取链请求默认超时（v3.0：脚本级 8 秒） */
    private val defaultRequestTimeoutMs: Long = 8_000,
    private val initTimeoutMs: Long = 10_000,
    /** 脚本 console 输出回调（level, text）；默认丢弃，App 层接 Log 便于排查脚本问题 */
    private val consoleSink: (level: String, text: String) -> Unit = { _, _ -> },
    /** 脚本上报更新回调（原始 payload JSON）；App 层据此提示用户 */
    private val updateAlertSink: (payloadJson: String) -> Unit = {},
    /** 脚本持久化存储（v3.0 §3.2 localStorage）：App 层注入文件实现即可跨重启保留 */
    private val storage: LxStorage = LxMemoryStorage(),
) : AutoCloseable {

    private val gson = Gson()
    private var quickJs: QuickJs? = null
    private var pumpJob: Job? = null

    /** 同一实例的求值串行化（请求与定时器泵并发时保护 QuickJS 单线程语义） */
    private val evalMutex = Mutex()
    private var initDeferred: CompletableDeferred<LxInitedPayload>? = null
    private val pendingResults = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val idCounter = AtomicLong(1)

    var header: LxScriptHeader? = null
        private set
    var initedPayload: LxInitedPayload? = null
        private set

    /** 脚本上报的更新信息（原始 JSON）；null = 未上报 */
    var reportedUpdateJson: String? = null
        private set

    val isLoaded: Boolean
        get() = quickJs != null

    /** 加载并初始化脚本；脚本必须在此期间 send(inited)，否则超时报错 */
    suspend fun load(
        script: String,
        scriptInfo: LxCurrentScriptInfo = LxCurrentScriptInfo(),
    ): LxInitedPayload {
        check(quickJs == null) { "engine already loaded" }
        header = LxScriptHeaderParser.parse(script)

        val deferred = CompletableDeferred<LxInitedPayload>()
        initDeferred = deferred
        val qjs = QuickJs.create(Dispatchers.Default)
        quickJs = qjs
        try {
            registerNatives(qjs, scriptInfo)
            // 附加 ";void 0" 使脚本完成值为 undefined，避免 evaluate<Unit> 的类型转换问题
            qjs.evaluate<Unit>(shim(gson.toJson(scriptInfo)) + "\n;void 0")
            // rawScript：部分脚本读自身源码做自更新（落雪 currentScriptInfo.rawScript）
            runCatching {
                qjs.evaluate<Unit>("globalThis.__lx_script_info.rawScript = ${gson.toJson(script)};\n;void 0")
            }
            qjs.evaluate<Unit>(script + "\n;void 0")
            // 定时器泵在 inited 之后才启动：加载期（混淆脚本的字符串解码很重）插队求值会拖慢甚至拖超时
            val payload = withTimeout(initTimeoutMs) { deferred.await() }
            startTimerPump(qjs)
            return payload
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * 定时器泵：脚本用 setTimeout/setInterval 做延迟初始化与更新检查（星海音乐源等）。
     * JS 侧只登记，这里按 tick 驱动；与请求共用一把锁，避免同一实例并发求值。
     */
    private fun startTimerPump(qjs: QuickJs) {
        if (pumpJob != null) return
        pumpJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                delay(300)
                if (quickJs !== qjs) return@launch
                runCatching {
                    evalMutex.withLock {
                        qjs.evaluate<Int>("\n__lx_pump_timers()\n")
                    }
                }
            }
        }
    }

    /**
     * 走脚本取播放链接：request({source, action:'musicUrl', info:{type, musicInfo}})
     * 返回值校验对齐 preload.js：必须 http(s) 且长度 ≤ 2048。
     */
    suspend fun resolveMusicUrl(
        source: String,
        quality: String,
        musicInfoJson: String,
        timeoutMs: Long = defaultRequestTimeoutMs,
    ): String {
        val qjs = quickJs ?: error("engine not loaded")
        val deferred = CompletableDeferred<String>()
        val id = idCounter.getAndIncrement()
        pendingResults[id] = deferred
        val argsJson = gson.toJson(
            mapOf(
                "source" to source,
                "action" to "musicUrl",
                "info" to mapOf(
                    "type" to quality,
                    "musicInfo" to JsonParser.parseString(musicInfoJson),
                ),
            ),
        )
        val expr = """
            await (async () => {
              const r = await globalThis.__lx_dispatch_request($id, ${jStr(argsJson)});
              globalThis.__lx_report_result($id, JSON.stringify(r));
            })()
        """.trimIndent()
        val resultJson: String = try {
            withTimeout(timeoutMs) {
                qjs.evaluate<Unit>(expr)
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingResults.remove(id)
            throw LxScriptTimeoutException("脚本请求超时（${timeoutMs}ms）")
        } catch (e: LxScriptRequestException) {
            pendingResults.remove(id)
            throw e
        } catch (e: QuickJsException) {
            pendingResults.remove(id)
            throw LxScriptRequestException("脚本执行失败: ${e.message}")
        }
        pendingResults.remove(id)
        return parseMusicUrlResult(resultJson)
    }

    /** 测试/诊断用：在脚本上下文里求值一个表达式（与请求共用锁）；结果统一按可空类型读出 */
    suspend inline fun <reified T> evalForTest(expression: String): T? = evalRaw(expression) as? T

    @PublishedApi
    internal suspend fun evalRaw(expression: String): Any? {
        val qjs = quickJs ?: return null
        return evalMutex.withLock {
            runCatching { qjs.evaluate<Any?>("\n($expression)\n") }.getOrNull()
        }
    }

    override fun close() {
        pumpJob?.cancel()
        pumpJob = null
        runCatching { quickJs?.close() }
        quickJs = null
        initDeferred = null
        pendingResults.clear()
    }

    // ---------- 内部实现 ----------

    private fun parseMusicUrlResult(resultJson: String): String {
        val el = JsonParser.parseString(resultJson)
        if (el.isJsonObject) {
            val err = el.asJsonObject.get("__lx_error")?.takeIf { !it.isJsonNull }?.asString
            if (err != null) throw LxScriptRequestException(err)
        }
        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
            val url = el.asString
            if (Regex("^https?://").containsMatchIn(url) && url.length <= 2048) return url
            throw LxScriptRequestException("脚本返回的 URL 非法（须 http(s) 且 ≤2048 字符）")
        }
        throw LxScriptRequestException("脚本返回类型无效（musicUrl 期望字符串）")
    }

    private fun parseInitedPayload(payloadJson: String): LxInitedPayload {
        val root = JsonParser.parseString(payloadJson).asJsonObject
        val sourcesJson = root.getAsJsonObject("sources")
        val sources = LinkedHashMap<String, LxSourceInfo>()
        sourcesJson?.entrySet()?.forEach { (key, value) ->
            if (key !in LxSpec.ALLOWED_SOURCES || !value.isJsonObject) return@forEach
            val o = value.asJsonObject
            val allowedActions = LxSpec.allowedActions(key)
            val actions = o.getAsJsonArray("actions")?.map { it.asString }
                ?.filter { it in allowedActions } ?: emptyList()
            val qualitys = o.getAsJsonArray("qualitys")?.map { it.asString }
                ?.filter { it in LxSpec.ALLOWED_QUALITYS } ?: emptyList()
            sources[key] = LxSourceInfo(
                name = key,
                type = o.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "music",
                actions = actions,
                qualitys = qualitys,
            )
        }
        val openDevTools = root.get("openDevTools")?.takeIf { it.isJsonPrimitive }?.asBoolean
        return LxInitedPayload(openDevTools, sources)
    }

    private fun registerNatives(qjs: QuickJs, scriptInfo: LxCurrentScriptInfo) {
        qjs.defineBinding("__lx_send", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? {
                val name = args.getOrNull(0) as? String
                val payloadJson = args.getOrNull(1) as? String ?: "{}"
                when (name) {
                    LxEventNames.INITED -> {
                        val payload = parseInitedPayload(payloadJson)
                        initedPayload = payload
                        initDeferred?.complete(payload)
                    }
                    // 脚本自报更新（落雪对齐）：每个脚本实例只接受一次，后续忽略
                    LxEventNames.UPDATE_ALERT -> {
                        if (reportedUpdateJson == null) {
                            reportedUpdateJson = payloadJson
                            runCatching { updateAlertSink(payloadJson) }
                        }
                    }
                }
                return null
            }
        })
        qjs.defineBinding("__lx_native_md5", object : FunctionBinding<String> {
            override fun invoke(args: Array<Any?>): String =
                Md5.hex(args.getOrNull(0)?.toString() ?: "")
        })
        qjs.defineBinding("__lx_native_request", object : AsyncFunctionBinding<String> {
            override suspend fun invoke(args: Array<Any?>): String {
                val url = args.getOrNull(0) as? String ?: ""
                val optionsJson = args.getOrNull(1) as? String
                return withContext(Dispatchers.IO) {
                    gson.toJson(LxHttp.request(url, optionsJson, allowedHosts))
                }
            }
        })
        // ── lx.utils 桥（对齐落雪 preload.js 的脚本可见面；Node Buffer/crypto/zlib 的等价实现）──
        qjs.defineBinding("__lx_buf_from", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? = LxByteUtils.bufferFrom(args.getOrNull(0), args.getOrNull(1) as? String)
        })
        qjs.defineBinding("__lx_buf_to_string", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? =
                LxByteUtils.bufToString(args.getOrNull(0), args.getOrNull(1) as? String)
        })
        qjs.defineBinding("__lx_aes", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? = LxByteUtils.aesEncrypt(
                args.getOrNull(0), args.getOrNull(1) as? String, args.getOrNull(2), args.getOrNull(3),
            )
        })
        qjs.defineBinding("__lx_rsa", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? =
                LxByteUtils.rsaEncrypt(args.getOrNull(0), args.getOrNull(1) as? String)
        })
        qjs.defineBinding("__lx_random_bytes", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? =
                LxByteUtils.randomBytes((args.getOrNull(0) as? Number)?.toInt() ?: 0)
        })
        qjs.defineBinding("__lx_zlib_inflate", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? = LxByteUtils.zlibInflate(args.getOrNull(0))
        })
        qjs.defineBinding("__lx_zlib_deflate", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? = LxByteUtils.zlibDeflate(args.getOrNull(0))
        })
        qjs.defineBinding("__lx_console", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? {
                val level = args.getOrNull(0) as? String ?: "log"
                val text = args.drop(1).joinToString(" ") { it?.toString() ?: "null" }
                runCatching { consoleSink(level, text) }
                return null
            }
        })
        // 受限 localStorage 的 native 支撑（读写由 App 注入的 storage 负责）
        qjs.defineBinding("__lx_storage_get", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? = storage.get(args.getOrNull(0)?.toString() ?: "")
        })
        qjs.defineBinding("__lx_storage_set", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? {
                storage.set(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1)?.toString() ?: "")
                return null
            }
        })
        qjs.defineBinding("__lx_storage_remove", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? {
                storage.remove(args.getOrNull(0)?.toString() ?: "")
                return null
            }
        })
        qjs.defineBinding("__lx_storage_clear", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? {
                storage.clear()
                return null
            }
        })
        qjs.defineBinding("__lx_storage_keys", object : FunctionBinding<String> {
            override fun invoke(args: Array<Any?>): String = gson.toJson(storage.keys())
        })
        qjs.defineBinding("__lx_report_result", object : FunctionBinding<Any?> {
            override fun invoke(args: Array<Any?>): Any? {
                val id = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val json = args.getOrNull(1) as? String ?: "null"
                pendingResults.remove(id)?.complete(json)
                return null
            }
        })
    }

    private fun jStr(s: String): String = gson.toJson(s)

    /** 注入的 JS shim：把 native 全局函数组装成 lx API（对齐 preload.js 的脚本可见面） */
    private fun shim(scriptInfoJson: String): String = """
        globalThis.__lx_handlers = { request: null };
        globalThis.__lx_script_info = $scriptInfoJson;
        // 落雪 preload 提供 console；混淆脚本常在加载期打日志，缺失会直接 ReferenceError
        globalThis.console = (function () {
          function emit(level) {
            return function () {
              var parts = [];
              for (var i = 0; i < arguments.length; i++) {
                var a = arguments[i];
                try { parts.push(typeof a === 'string' ? a : JSON.stringify(a)); }
                catch (e) { parts.push(String(a)); }
              }
              try { __lx_console(level, parts.join(' ')); } catch (e) {}
            };
          }
          return {
            log: emit('log'), info: emit('info'), warn: emit('warn'), error: emit('error'),
            debug: emit('debug'), trace: emit('trace'), dir: emit('log'), table: emit('log'),
            group: emit('group'), groupCollapsed: emit('group'), groupEnd: function () {},
            time: function () {}, timeEnd: function () {}, timeLog: function () {},
            assert: function (condition) { if (!condition) __lx_console('error', 'Assertion failed'); },
            count: function () {}, countReset: function () {}
          };
        })();
        // 浏览器环境最小面：落雪脚本跑在 Electron 渲染进程里，会直接引用 window/document/atob 等，
        // 缺失会导致加载期 ReferenceError（实测 FreeListen/洛雪音乐源 就卡在这里）
        globalThis.window = globalThis;
        globalThis.self = globalThis;
        globalThis.addEventListener = function () {};
        globalThis.removeEventListener = function () {};
        globalThis.atob = function (b64) {
          try { return __lx_buf_to_string(__lx_buf_from(String(b64), 'base64'), 'latin1'); } catch (e) { return ''; }
        };
        globalThis.btoa = function (s) {
          try { return __lx_buf_to_string(__lx_buf_from(String(s), 'latin1'), 'base64'); } catch (e) { return ''; }
        };
        globalThis.document = {
          createElement: function () {
            return { style: {}, setAttribute: function () {}, appendChild: function () {}, click: function () {},
                     addEventListener: function () {}, remove: function () {} };
          },
          getElementById: function () { return null; },
          querySelector: function () { return null; },
          addEventListener: function () {},
          body: { appendChild: function () {}, removeChild: function () {} },
          documentElement: { style: {} },
        };
        globalThis.location = { href: 'https://www.bilibili.com/', hostname: 'www.bilibili.com', protocol: 'https:' };
        globalThis.navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' };
        globalThis.process = { env: {}, platform: 'win32' };

        // 定时器：Node/浏览器都有，脚本常用 setTimeout 做延迟初始化与更新检查。
        // JS 侧只登记，Kotlin 侧按 tick 驱动 __lx_pump_timers（见 pumpTimers）。
        globalThis.__lx_timers = { next: 1, items: {} };
        globalThis.setTimeout = function (fn, ms) {
          var id = __lx_timers.next++;
          __lx_timers.items[id] = { fn: fn, at: Date.now() + (Number(ms) || 0), every: 0 };
          return id;
        };
        globalThis.setInterval = function (fn, ms) {
          var id = __lx_timers.next++;
          var every = Math.max(Number(ms) || 0, 10);
          __lx_timers.items[id] = { fn: fn, at: Date.now() + every, every: every };
          return id;
        };
        globalThis.clearTimeout = function (id) { delete __lx_timers.items[id]; };
        globalThis.clearInterval = globalThis.clearTimeout;
        globalThis.__lx_pump_timers = function () {
          var now = Date.now(), ran = 0;
          for (var id in __lx_timers.items) {
            var it = __lx_timers.items[id];
            if (!it || it.at > now) continue;
            if (it.every > 0) { it.at = now + it.every; } else { delete __lx_timers.items[id]; }
            try { it.fn(); } catch (e) { try { console.error('timer error: ' + e); } catch (_) {} }
            ran++;
          }
          return ran;
        };

        globalThis.lx = {
          EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
          version: '2.0.0',
          // 桌面端脚本预载面；个别源会按 env 差异走日志/协议分支
          env: globalThis.__lx_script_info.env || 'mobile',
          currentScriptInfo: globalThis.__lx_script_info,
          on: function (name, handler) {
            if (name === 'request') globalThis.__lx_handlers.request = handler;
            return Promise.resolve();
          },
          send: function (name, payload) {
            if (name === 'inited' && globalThis.__lx_is_inited) {
              return Promise.reject(new Error('Script is inited'));
            }
            if (name === 'updateAlert' && globalThis.__lx_update_alerted) {
              return Promise.reject(new Error('The update alert can only be called once.'));
            }
            if (name === 'updateAlert') {
              if (!payload || typeof payload !== 'object' || typeof payload.log !== 'string') {
                return Promise.reject(new Error('log is required.'));
              }
              globalThis.__lx_update_alerted = true;
            }
            if (name === 'inited') globalThis.__lx_is_inited = true;
            __lx_send(name, JSON.stringify(payload === undefined ? null : payload));
            return Promise.resolve();
          },
          showUpdateAlert: function () {},
          utils: {
            crypto: {
              md5: function (s) { return __lx_native_md5(String(s)); },
              aesEncrypt: function (buffer, mode, key, iv) { return __lx_aes(buffer, mode, key, iv); },
              rsaEncrypt: function (buffer, key) { return __lx_rsa(buffer, key); },
              randomBytes: function (size) { return __lx_random_bytes(size); }
            },
            buffer: {
              from: function () { return __lx_buf_from.apply(null, arguments); },
              bufToString: function (buf, format) { return __lx_buf_to_string(buf, format); }
            },
            zlib: {
              inflate: function (buf) { return Promise.resolve(__lx_zlib_inflate(buf)); },
              deflate: function (data) { return Promise.resolve(__lx_zlib_deflate(data)); }
            }
          },
          // 对齐落雪 needle 桥：callback(err, resp, body)，resp = {statusCode,statusMessage,headers,bytes,raw,body}
          // 且 resp.body 在可解析时自动 JSON 化；返回值是"中止函数"（脚本可能调用它取消请求）
          request: function (url, options, callback) {
            var aborted = false;
            var p = Promise.resolve(__lx_native_request(url, JSON.stringify(options || {})))
              .then(function (respJson) {
                var r = JSON.parse(respJson);
                if (r.err) {
                  if (callback) callback(r.err, null, null);
                  return { err: r.err, resp: null, body: null };
                }
                var raw = r.body === undefined ? null : r.body;
                var body = raw;
                try { body = JSON.parse(raw); } catch (e) {}
                var resp = {
                  statusCode: r.statusCode,
                  statusMessage: r.statusMessage || '',
                  headers: r.headers || {},
                  bytes: raw == null ? 0 : raw.length,
                  raw: raw,
                  body: body,
                };
                if (callback) callback(null, resp, body);
                return { err: null, resp: resp, body: body };
              });
            var abort = function () { aborted = true; };
            abort.then = function (onFulfilled, onRejected) { return p.then(onFulfilled, onRejected); };
            abort.catch = function (onRejected) { return p.catch(onRejected); };
            return abort;
          }
        };
        // ── v3.0 §3.2 桥接补齐：fetch / get / post 与受限 localStorage ──
        // fetch：现代语义，返回 Response-like（ok / status / headers / text() / json()）
        globalThis.fetch = function (url, options) {
          options = options || {};
          var method = (options.method || 'GET').toString().toUpperCase();
          var body = options.body;
          if (body !== undefined && body !== null && typeof body !== 'string') {
            try { body = JSON.stringify(body); } catch (e) { body = String(body); }
          }
          return new Promise(function (resolve, reject) {
            lx.request(url, { method: method, headers: options.headers || {}, body: body }, function (err, resp) {
              if (err) { reject(new Error(typeof err === 'string' ? err : JSON.stringify(err))); return; }
              var text = (resp && resp.raw != null) ? resp.raw : '';
              if (typeof text !== 'string') { try { text = JSON.stringify(text); } catch (e) { text = String(text); } }
              resolve({
                ok: resp.statusCode >= 200 && resp.statusCode < 300,
                status: resp.statusCode,
                statusText: resp.statusMessage || '',
                headers: resp.headers || {},
                url: url,
                text: function () { return Promise.resolve(text); },
                json: function () { return Promise.resolve(JSON.parse(text)); }
              });
            });
          });
        };
        // get / post：洛雪糖，直接给解析后的 body（与 lx.request 的回调形态一致，便于脚本二选一）
        function __lx_sugarRequest(url, options, method, body) {
          return new Promise(function (resolve, reject) {
            lx.request(url, { method: method, headers: (options && options.headers) || {}, body: body },
              function (err, resp, parsed) {
                if (err) { reject(new Error(typeof err === 'string' ? err : JSON.stringify(err))); return; }
                resolve({
                  statusCode: resp.statusCode,
                  statusMessage: resp.statusMessage || '',
                  headers: resp.headers || {},
                  raw: resp.raw,
                  body: parsed,
                  json: function () { return parsed; }
                });
              });
          });
        }
        globalThis.get = function (url, options) { return __lx_sugarRequest(url, options || {}, 'GET', undefined); };
        globalThis.post = function (url, body, options) {
          var b = body;
          if (b !== undefined && b !== null && typeof b !== 'string') {
            try { b = JSON.stringify(b); } catch (e) { b = String(b); }
          }
          return __lx_sugarRequest(url, options || {}, 'POST', b);
        };
        // 受限 localStorage：每个脚本一份（App 层按脚本持久化），仅脚本自身可见
        globalThis.localStorage = (function () {
          function allKeys() {
            try { return JSON.parse(__lx_storage_keys() || '[]'); } catch (e) { return []; }
          }
          var ls = {
            getItem: function (k) {
              var v = __lx_storage_get(String(k));
              return (v === null || v === undefined) ? null : String(v);
            },
            setItem: function (k, v) { __lx_storage_set(String(k), String(v)); },
            removeItem: function (k) { __lx_storage_remove(String(k)); },
            clear: function () { __lx_storage_clear(); },
            key: function (i) {
              var ks = allKeys();
              return (i >= 0 && i < ks.length) ? ks[i] : null;
            }
          };
          try { Object.defineProperty(ls, 'length', { get: function () { return allKeys().length; } }); } catch (e) {}
          return ls;
        })();
        // lx 命名空间下也挂一份（部分脚本写成 lx.get / lx.post）
        lx.fetch = globalThis.fetch;
        lx.get = globalThis.get;
        lx.post = globalThis.post;
        globalThis.__lx_dispatch_request = async function (id, argsJson) {
          const h = globalThis.__lx_handlers.request;
          if (!h) return { __lx_error: 'request handler not registered (missing lx.on(request))' };
          try {
            return await h(JSON.parse(argsJson));
          } catch (e) {
            return { __lx_error: String((e && e.message) ? e.message : e) };
          }
        };
    """.trimIndent()
}
