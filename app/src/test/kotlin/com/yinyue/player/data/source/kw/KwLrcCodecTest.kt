package com.yinyue.player.data.source.kw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class KwLrcCodecTest {

    /** 按服务端编码规则反向构造：文本 → XOR(yeelion) → base64 → deflate → tp=content 前缀 */
    private fun encode(lrcxText: String, isGetLyricx: Boolean): ByteArray {
        val payload: ByteArray = if (isGetLyricx) {
            val key = "yeelion".toByteArray(Charsets.UTF_8)
            val raw = lrcxText.toByteArray(Charsets.UTF_8)
            val xored = ByteArray(raw.size)
            var i = 0
            var k = 0
            while (i < raw.size) {
                xored[i] = (raw[i].toInt() xor key[k].toInt()).toByte()
                i++
                k = (k + 1) % key.size
            }
            Base64.getEncoder().encode(xored)
        } else {
            lrcxText.toByteArray(Charsets.UTF_8)
        }
        val deflater = Deflater()
        deflater.setInput(payload)
        deflater.finish()
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (!deflater.finished()) buf.write(tmp, 0, deflater.deflate(tmp))
        deflater.end()
        val head = "tp=content\r\n\r\n".toByteArray(Charsets.UTF_8)
        return head + buf.toByteArray()
    }

    @Test
    fun `yeelion 解码往返`() {
        val text = "[00:01.00]<0,100>晴<100,100>天\n[00:05.00]故事的小黄花"
        val decoded = KwLrcCodec.decode(encode(text, isGetLyricx = true), isGetLyricx = true)
        assertEquals(text, decoded)
    }

    @Test
    fun `parseLrc 剥离逐字标记并产出标准LRC`() {
        val lrcx = "[00:01.00]<0,100>晴<100,100>天\n[00:05.00]故事的小黄花\n[ti:晴天]\n"
        val lrc = KwLrcCodec.parseLrc(lrcx)
        assertNotNull(lrc)
        assertTrue(lrc!!.contains("[00:01.000]晴天"))
        assertTrue(lrc.contains("[00:05.000]故事的小黄花"))
        assertTrue(lrc.contains("[ti:晴天]"))
        assertTrue("逐字标记应被剥离", !lrc.contains("<0,100>"))
    }

    @Test
    fun `无时间行视为失败`() {
        assertNull(KwLrcCodec.parseLrc("这不是歌词"))
        assertEquals("", KwLrcCodec.decode("not-tp=content".toByteArray(), isGetLyricx = true))
    }
}
