package com.yinyue.player.mv.bilibili

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 冒烟：真实调用 B 站接口（限频自律，共 3~4 次请求）。
 * 1) nav 取 WBI 密钥；2) buvid Cookie；3) WBI 签名搜索"音乐区"，期望 code=0（未触发 -352）。
 */
class BiliClientSmokeTest {

    private val client = BiliClient()

    @Test
    fun `nav 接口匿名获取 WBI 密钥`() = runBlocking {
        val keys = client.fetchWbiKeys()
        assertTrue("imgKey 应为非空十六进制串: ${keys.imgKey}", keys.imgKey.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue("subKey 应为非空十六进制串: ${keys.subKey}", keys.subKey.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `WBI 签名搜索真实可用且未触发风控`() = runBlocking {
        val keys = client.fetchWbiKeys()
        client.fetchBuvidCookie() // 冷启动收 buvid（规避软风控）
        Thread.sleep(1500) // 令牌桶 1次/1.5s 的自我约束
        var result = client.searchVideo("周杰伦 晴天 MV", keys)
        if (result.numResults == 0) {
            Thread.sleep(2500)
            result = client.searchVideo("晴天", keys)
        }
        assertEquals("应返回 code=0（若为 -352/-412 说明签名或风控策略需调整）", 0, result.code)
        assertTrue("两次尝试后仍无结果", result.numResults > 0)
    }
}
