package com.yinyue.domain.normalize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 显示名清洗测试（用户要求：歌名干净、加了后缀的一律按核心名去重） */
class NameCleanerTest {

    @Test
    fun `音频核心歌名 去掉拼接与后缀`() {
        assertEquals("晴天", NameCleaner.coreSongName("晴天"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天+告白气球 (Live)"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天+告白气球"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天 - Live版"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天（周杰伦）"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天 [Official Audio]"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天 feat. 五月天"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天 ft.阿信"))
        assertEquals("晴天", NameCleaner.coreSongName("晴天《不能说的秘密》插曲"))
        // 空结果回退
        assertEquals("(Live)", NameCleaner.coreSongName("(Live)"))
    }

    @Test
    fun `核心键用于去重判定 后缀差异归一到同键`() {
        val keys = listOf(
            "晴天",
            "晴天 (Live)",
            "晴天+告白气球",
            "晴天 - Live版",
            "晴天（周杰伦）",
        ).map { NameCleaner.coreKey(it) }.distinct()
        assertEquals(1, keys.size)
        assertEquals(NameCleaner.coreKey("晴天"), keys.first())
    }

    @Test
    fun `MV标题清洗 提取歌名`() {
        assertEquals("Umbrella", NameCleaner.cleanMvTitle("【4K】蕾哈娜 x JAY-Z 经典神曲《Umbrella》MV 2007 超分画质增强版"))
        assertEquals("告白气球", NameCleaner.cleanMvTitle("周杰伦《告白气球》官方MV 1080P"))
        assertEquals("晴天", NameCleaner.cleanMvTitle("晴天 MV 高清"))
        // 无法提取时保留原文（不清空）
        val raw = "[Wayward]9/15号录播 qingtian代播"
        assertTrue(NameCleaner.cleanMvTitle(raw).isNotBlank())
    }

    @Test
    fun `歌手搜索识别与歌名等于歌手名的过滤`() {
        val wang = NameCleaner.coreKey("周杰伦")
        // 歌手匹配（含多歌手字段与别名写法）
        assertTrue(NameCleaner.artistMatches("周杰伦", wang))
        assertTrue(NameCleaner.artistMatches("周杰伦、方文山", wang))
        assertTrue(NameCleaner.artistMatches("Jay Chou", wang)) // 别名归一后
        assertFalse(NameCleaner.artistMatches("五月天", wang))
        assertFalse(NameCleaner.artistMatches("", wang))
        // 歌名等于歌手名 → 脏数据，歌手搜索时应过滤
        assertTrue(NameCleaner.songNameEqualsArtist("周杰伦", "周杰伦"))
        assertTrue(NameCleaner.songNameEqualsArtist("周杰伦", "周杰伦、方文山"))
        assertFalse(NameCleaner.songNameEqualsArtist("晴天", "周杰伦"))
        assertFalse(NameCleaner.songNameEqualsArtist("以父之名", "周杰伦"))
    }

    @Test
    fun `MV清洗后命中同一首歌则判定重复`() {
        // 两个不同标题的同一首歌 MV：清洗后核心键一致
        val a = NameCleaner.coreKey(NameCleaner.cleanMvTitle("【4K】蕾哈娜 x JAY-Z 经典神曲《Umbrella》MV 2007 超分画质增强版"))
        val b = NameCleaner.coreKey(NameCleaner.cleanMvTitle("Umbrella 官方MV"))
        assertEquals(a, b)
    }
}
