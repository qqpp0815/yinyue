package com.yinyue.player.mv.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WbiSignerTest {

    // bilibili-API-collect 文档中的示例密钥
    private val imgKey = "7cd084941338484aae1ad9425b54b750"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `mixinKey 按混淆表重排取前32位`() {
        val key = WbiSigner.mixinKey(imgKey, subKey)
        assertEquals(32, key.length)
        // 来自 bilibili-API-collect WBI 文档示例的期望值
        assertEquals("ea1db124af307062474693fa7b4f4ff8", key)
    }

    @Test
    fun `buildQuery 参数排序且过滤 bang 等字符并空格转百分二十`() {
        val query = WbiSigner.buildQuery(
            params = mapOf(
                "b" to "x y!z",
                "a" to "1",
                "keyword" to "周杰伦 晴天",
            ),
            wts = 1702204801,
        )
        // 排序：a, b, keyword, wts；value 过滤 !'()*；空格 → %20
        assertEquals("a=1&b=x%20yz&keyword=${WbiSigner.urlEncode("周杰伦 晴天")}&wts=1702204801", query)
    }

    @Test
    fun `sign 产出 32 位十六进制 w_rid 且随 wts 变化`() {
        val s1 = WbiSigner.sign(mapOf("keyword" to "晴天"), imgKey, subKey, 1702204801)
        val s2 = WbiSigner.sign(mapOf("keyword" to "晴天"), imgKey, subKey, 1702204802)
        assertTrue(s1.getValue("w_rid").matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(s1.getValue("wts") == "1702204801")
        assertTrue("不同 wts 应产生不同签名", s1.getValue("w_rid") != s2.getValue("w_rid"))
    }
}
