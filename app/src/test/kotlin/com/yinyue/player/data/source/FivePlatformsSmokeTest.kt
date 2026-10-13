package com.yinyue.player.data.source

import com.yinyue.player.data.source.kg.KgProvider
import com.yinyue.player.data.source.kw.KwProvider
import com.yinyue.player.data.source.mg.MgProvider
import com.yinyue.player.data.source.tx.TxProvider
import com.yinyue.player.data.source.wy.WyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6a~6d 冒烟：五平台真实搜索各 1 次（限频自律：平台间 1.5s 间隔）。
 * 任一平台接口失效不应阻塞其他平台（失败隔离），此处打印并断言至少 3 家可用。
 */
class FivePlatformsSmokeTest {

    private val providers = listOf(KwProvider(), WyProvider(), KgProvider(), TxProvider(), MgProvider())

    @Test
    fun `五平台真实搜索`() = runBlocking {
        var okCount = 0
        for (p in providers) {
            val ok = runCatching {
                val page = p.search("晴天 周杰伦", 1, 10)
                page.list.isNotEmpty()
            }.getOrElse { e ->
                println("[${p.platform}] 失败: ${e.message}")
                false
            }
            if (ok) okCount++
            println("[${p.platform}] ok=$ok")
            Thread.sleep(1500) // 平台间限频自律
        }
        println("可用平台数: $okCount/5")
        assertTrue("至少 3 家平台可用，实际 $okCount", okCount >= 3)
    }
}
