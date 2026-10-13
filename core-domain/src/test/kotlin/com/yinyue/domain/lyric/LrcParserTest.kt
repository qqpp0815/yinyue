package com.yinyue.domain.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    private val sample = """
        [ti:晴天]
        [ar:周杰伦]
        [al:叶惠美]
        [offset:500]
        [00:04.29]故事的小黄花
        [00:10][00:23]从出生那年就飘着
        [00:12.50]<00:12.50>童~年的荡秋千
        非歌词行
        随记忆一直晃
    """.trimIndent()

    @Test
    fun `解析时间轴_元数据_offset_多标签与异常行`() {
        val data = LrcParser.parse(sample)
        assertEquals("晴天", data.title)
        assertEquals("周杰伦", data.artist)
        assertEquals("叶惠美", data.album)
        assertEquals(500L, data.offsetMs)
        // [00:10][00:23] 多标签展开为两行，共 4 行；无标签行跳过
        assertEquals(4, data.lines.size)
        assertEquals(4290L, data.lines[0].timeMs)
        assertEquals("故事的小黄花", data.lines[0].text)
        assertEquals(10000L, data.lines[1].timeMs)
        assertEquals(12500L, data.lines[2].timeMs)
        assertEquals("童~年的荡秋千", data.lines[2].text) // 逐字标记剥离
        assertEquals(23000L, data.lines[3].timeMs)
        assertEquals("从出生那年就飘着", data.lines[3].text)
    }

    @Test
    fun `二分查找当前行`() {
        val data = LrcParser.parse(sample)
        assertEquals(-1, LrcParser.findCurrentLine(data.lines, 1000))
        assertEquals(0, LrcParser.findCurrentLine(data.lines, 5000))
        assertEquals(1, LrcParser.findCurrentLine(data.lines, 12000)) // 尚未到 12.5s 行
        assertEquals(3, LrcParser.findCurrentLine(data.lines, 99999))
    }

    @Test
    fun `500行解析性能低于10毫秒`() {
        val sb = StringBuilder()
        for (i in 0 until 500) {
            sb.append('[').append(i / 60).append(':').append(i % 60).append(".50]第").append(i).append("行\n")
        }
        val text = sb.toString()
        LrcParser.parse(text) // JIT 预热
        val start = System.nanoTime()
        val data = LrcParser.parse(text)
        val costMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(500, data.lines.size)
        assert(costMs < 10) { "解析耗时 ${costMs}ms" }
    }
}
