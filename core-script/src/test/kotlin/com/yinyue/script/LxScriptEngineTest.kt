package com.yinyue.script

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 2 冒烟：QuickJS 桥的"初始化一次 → inited → 长驻 request"事件模型。
 * 包含真实网络请求（www.baidu.com）与真实 md5 校验。
 */
class LxScriptEngineTest {

    private fun fixture(): String =
        javaClass.getResourceAsStream("/lx/fake_lx_source.js")!!
            .readBytes().decodeToString()

    private val musicInfoJson = """
        {"songmid":"001","name":"晴天","singer":"周杰伦","source":"kw",
         "interval":"04:29","albumId":"1001","types":[],"_types":{},"typeUrl":{}}
    """.trimIndent()

    @Test
    fun `load 解析 inited payload 并做白名单交集`() = runBlocking {
        LxScriptEngine().use { engine ->
            val payload = engine.load(
                fixture(),
                LxCurrentScriptInfo(name = "冒烟测试源", version = "1.0.0"),
            )
            val kw = payload.sources.getValue("kw")
            assertEquals("music", kw.type)
            assertEquals(listOf("musicUrl"), kw.actions)
            assertEquals(listOf("128k", "320k", "flac"), kw.qualitys)
            assertNull(payload.sources["tx"])
            assertEquals("冒烟测试源", engine.header?.name)
            assertEquals("1.0.0", engine.header?.version)
        }
    }

    @Test
    fun `取链请求走通脚本handler并带真实http与md5`() = runBlocking {
        LxScriptEngine().use { engine ->
            engine.load(fixture())
            val url = engine.resolveMusicUrl("kw", "320k", musicInfoJson)
            // 期望脚本拼接：https://example.com/audio/320k.mp3?md5=<md5(abc)>&status=<baidu状态码>
            assertTrue("url=$url", url.startsWith("https://example.com/audio/320k.mp3?md5="))
            assertTrue("md5(abc) 应出现在 URL: $url", url.contains("900150983cd24fb0d6963f7d28e17f72"))
            assertTrue("baidu 状态码应为 200: $url", url.endsWith("status=200"))
        }
    }

    @Test
    fun `永不完成的handler按超时打断`() = runBlocking {
        val hangingScript = """
            /**
             * @name 挂起源
             * @version 1.0.0
             */
            lx.on(lx.EVENT_NAMES.request, async function () {
              return new Promise(function () {});
            });
            lx.send(lx.EVENT_NAMES.inited, { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });
        """.trimIndent()
        LxScriptEngine().use { engine ->
            engine.load(hangingScript)
            try {
                engine.resolveMusicUrl("kw", "128k", musicInfoJson, timeoutMs = 300)
                fail("应抛出超时异常")
            } catch (e: LxScriptTimeoutException) {
                assertTrue(e.message!!.contains("300"))
            }
        }
    }

    @Test
    fun `脚本加载期使用 console 不会中断导入`() = runBlocking {
        // 混淆脚本（如 jsjiami）常在加载期打日志；落雪 preload 提供 console，缺失会 ReferenceError 导致导入失败
        val noisy = """
            /**
             * @name 会打日志的源
             * @version 1.0.0
             */
            console.log('init', { a: 1 }, [1, 2]);
            console.warn('warn');
            console.error(new Error('boom'));
            console.debug('dbg');
            console.info('info');
            lx.on(lx.EVENT_NAMES.request, async function (req) {
              if (req.action === 'musicUrl') return 'https://example.com/a.mp3';
              return null;
            });
            lx.send(lx.EVENT_NAMES.inited, { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });
        """.trimIndent()
        val logs = mutableListOf<String>()
        LxScriptEngine(consoleSink = { level, text -> logs.add("$level:$text") }).use { engine ->
            engine.load(noisy)
            val firstLog = logs.first { it.startsWith("log:") }.removePrefix("log:")
            assertTrue("多参数应拼接且对象/数组可序列化: $firstLog", firstLog.startsWith("init "))
            assertTrue("对象参数应被序列化: $firstLog", firstLog.contains("{\"a\":1}"))
            assertTrue("数组参数应被序列化: $firstLog", firstLog.contains("[1,2]"))
            assertTrue("warn 应被捕获", logs.any { it.startsWith("warn:") })
            assertTrue("error 应被捕获", logs.any { it.startsWith("error:") })
            assertEquals(
                "https://example.com/a.mp3",
                engine.resolveMusicUrl("kw", "128k", musicInfoJson),
            )
        }
    }

    @Test
    fun `脚本上报更新遵守落雪契约（log 必填且只允许一次）`() = runBlocking {
        // 落雪 preload：updateAlert 需 data.log（字符串），否则 reject('log is required.')；
        // 且每个脚本实例只允许上报一次（reject('The update alert can only be called once.')）
        val script = """
            /**
             * @name 会报更新的源
             * @version 1.0.0
             */
            globalThis.__r1 = 'pending';
            globalThis.__r2 = 'pending';
            globalThis.__r3 = 'pending';
            lx.send(lx.EVENT_NAMES.updateAlert, { version: '9.9.9' }).catch(e => { globalThis.__r1 = e.message; });
            lx.send(lx.EVENT_NAMES.updateAlert, { log: '有新版本 2.0.0，支持新平台', updateUrl: 'https://example.com/s.js' }).then(() => { globalThis.__r2 = 'ok'; });
            lx.send(lx.EVENT_NAMES.updateAlert, { log: '再来一次' }).catch(e => { globalThis.__r3 = e.message; });
            lx.on(lx.EVENT_NAMES.request, async function () { return null; });
            lx.send(lx.EVENT_NAMES.inited, { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });
        """.trimIndent()
        val alerts = mutableListOf<String>()
        LxScriptEngine(updateAlertSink = { alerts.add(it) }).use { engine ->
            engine.load(script)
            assertEquals("缺 log 应被拒、仅接受一次合法上报", 1, alerts.size)
            assertTrue("上报内容应含 log 文案", alerts.first().contains("有新版本 2.0.0"))
            assertTrue("上报应带 updateUrl", alerts.first().contains("updateUrl"))
            val r1 = engine.evalForTest<String>("globalThis.__r1")
            val r2 = engine.evalForTest<String>("globalThis.__r2")
            val r3 = engine.evalForTest<String>("globalThis.__r3")
            assertEquals("log is required.", r1)
            assertEquals("ok", r2)
            assertEquals("The update alert can only be called once.", r3)
        }
    }

    @Test
    fun `提供浏览器与定时器环境（落雪脚本依赖）`() = runBlocking {
        // window/document/atob 等缺失会导致加载期 ReferenceError；
        // setTimeout 是星海音乐源等脚本的延迟初始化所必需
        val script = """
            /**
             * @name 环境依赖源
             */
            globalThis.__win = (typeof window !== 'undefined') && (window === globalThis);
            globalThis.__doc = typeof document.createElement === 'function';
            globalThis.__b64 = atob('aGVsbG8=');
            setTimeout(function () { globalThis.__t = 'fired'; }, 10);
            lx.on(lx.EVENT_NAMES.request, async function () { return null; });
            lx.send(lx.EVENT_NAMES.inited, { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });
        """.trimIndent()
        LxScriptEngine().use { engine ->
            engine.load(script)
            assertEquals(true, engine.evalForTest<Boolean>("globalThis.__win"))
            assertEquals(true, engine.evalForTest<Boolean>("globalThis.__doc"))
            assertEquals("hello", engine.evalForTest<String>("globalThis.__b64"))
            // 定时器由 Kotlin 侧的泵驱动
            var fired = false
            repeat(20) {
                if (engine.evalForTest<String?>("globalThis.__t ? 'fired' : null") == "fired") fired = true
                if (!fired) kotlinx.coroutines.delay(150)
            }
            assertTrue("setTimeout 回调应由定时器泵执行", fired)
        }
    }

    @Test
    fun `域名白名单拦截非白名单请求`() = runBlocking {
        // fixture 请求 www.baidu.com，不在白名单内 → handler 收到 err 后抛错 → 归一为 LxScriptRequestException
        LxScriptEngine(allowedHosts = setOf("api.allowed-only.com")).use { engine ->
            engine.load(fixture())
            try {
                engine.resolveMusicUrl("kw", "320k", musicInfoJson)
                fail("应被白名单拦截")
            } catch (e: LxScriptRequestException) {
                assertTrue(e.message!!.contains("domain not allowed"))
            }
        }
    }

    /** 记录型 storage：验证 localStorage 是否落到 App 注入的实现（App 层即文件实现） */
    private class RecordingStorage : LxStorage {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun clear() {
            map.clear()
        }

        override fun keys(): List<String> = map.keys.toList()
    }

    /**
     * v3.0 §3.2 桥接补齐（fetch / get / post / localStorage）。
     * shim 是一大段 JS 字符串，写错会波及所有脚本，所以这里把可见面钉住。
     */
    @Test
    fun `桥接提供 fetch-get-post 与 localStorage 并落到注入的存储`() = runBlocking {
        val storage = RecordingStorage()
        LxScriptEngine(storage = storage).use { engine ->
            engine.load(fixture())

            val types = engine.evalForTest<String>(
                "typeof fetch + ',' + typeof get + ',' + typeof post + ',' + typeof localStorage.setItem + ',' + typeof lx.get",
            )
            assertEquals("function,function,function,function,function", types)

            // 写入 → 立即落到注入实现（App 层为按脚本落盘的 JSON）
            val first = engine.evalForTest<String>(
                "(function(){ localStorage.setItem('k1','v1'); localStorage.setItem('k2','v2'); return localStorage.getItem('k1'); })()",
            )
            assertEquals("v1", first)
            assertEquals("v1", storage.map["k1"])
            assertEquals("v2", storage.map["k2"])
            assertEquals("2", engine.evalForTest<String>("String(localStorage.length)"))
            assertEquals("k1", engine.evalForTest<String>("String(localStorage.key(0))"))

            // 删除与清空
            engine.evalForTest<String>("(function(){ localStorage.removeItem('k1'); return 'ok'; })()")
            assertNull(storage.map["k1"])
            assertEquals("1", engine.evalForTest<String>("String(localStorage.length)"))
            engine.evalForTest<String>("(function(){ localStorage.clear(); return 'ok'; })()")
            assertEquals(0, storage.map.size)
        }
    }

    /** get/post 失败路径要 reject（证明包装层真的把错误透出来，而不是静默 resolve） */
    @Test
    fun `get 请求失败会 reject`() = runBlocking {
        LxScriptEngine().use { engine ->
            engine.load(fixture())
            val result = engine.evalForTest<String>(
                "await (async function(){ try { await get('https://127.0.0.1:9/nothing'); return 'resolved'; } catch (e) { return 'rejected'; } })()",
            )
            assertEquals("rejected", result)
        }
    }}
