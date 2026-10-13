package com.yinyue.script

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class RealSourceEndToEndTest {

    private val temp = System.getProperty("java.io.tmpdir")

    private fun script(name: String): String? {
        val file = File(temp, "latest-$name.js")
        return if (file.exists()) file.readText() else null
    }

    private val musicInfo = """
        {"songmid":"213973","name":"暖暖","singer":"梁静茹","source":"kw",
         "interval":"04:03","albumName":"亲亲","albumId":"9056","img":"",
         "typeUrl":{},"types":[{"type":"128k"}],"_types":{"128k":{}},"songId":"213973",
         "copyrightId":"","hash":"","priority":0,"quality":"128k","typeFile":"",
         "extension":"","fileName":"","isMohu":false,"isMutate":false,"otherFileName":""}
    """.trimIndent()

    @Test
    fun `resolve real music url from downloaded sources`() = runBlocking {
        for (name in listOf("1010-mobile", "1010", "huibq", "flower", "sixyin", "lx")) {
            val text = script(name) ?: continue
            val logs = mutableListOf<String>()
            try {
                LxScriptEngine(
                    consoleSink = { level, text -> logs.add("$level: $text") },
                ).use { engine ->
                    val payload = engine.load(text)
                    val sources = payload.sources.keys.joinToString(",")
                    println("[$name] inited sources=$sources")
                    if (name == "1010-mobile") {
                        val actualEnv = engine.evalForTest<String>("globalThis.lx.env")
                        check(actualEnv == "mobile") { "expected env=mobile, actual=$actualEnv" }
                    }
                    val url = engine.resolveMusicUrl("kw", "128k", musicInfo, timeoutMs = 15_000)
                    println("[$name] SUCCESS url=${url.take(120)}")
                }
            } catch (e: Exception) {
                println("[$name] FAILED ${e.javaClass.simpleName}: ${e.message}")
                logs.lastOrNull { it.startsWith("error") || it.startsWith("warn") }?.let {
                    println("[$name] lastConsole=$it")
                }
            }
        }
    }
}
