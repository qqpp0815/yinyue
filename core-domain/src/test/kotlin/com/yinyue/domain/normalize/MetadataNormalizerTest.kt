package com.yinyue.domain.normalize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataNormalizerTest {

    @Test
    fun `歌名清洗覆盖括号_横杠后缀_空格_繁简_大小写`() {
        assertEquals("晴天", MetadataNormalizer.normalizeSong("晴天"))
        assertEquals("晴天", MetadataNormalizer.normalizeSong("晴天（Live）"))
        assertEquals("晴天", MetadataNormalizer.normalizeSong("晴天[Live]"))
        assertEquals("晴天", MetadataNormalizer.normalizeSong("晴 天 "))
        assertEquals("晴天", MetadataNormalizer.normalizeSong("晴天 - Live版"))
        assertEquals("晴天", MetadataNormalizer.normalizeSong("晴天（周杰伦）- Live"))
        assertEquals("qingtian", MetadataNormalizer.normalizeSong("Qing Tian（晴天）")) // 括号及内容整体删除
        assertEquals("甜蜜蜜", MetadataNormalizer.normalizeSong("甜蜜蜜「现场版」"))
        assertEquals("光年之外片尾曲", MetadataNormalizer.normalizeSong("光年之外《太空旅客》片尾曲")) // 括号外文本保留
        assertEquals("好的晚安", MetadataNormalizer.normalizeSong("好的晚安（Accompaniment）"))
        // 全半角与大小写
        assertEquals("dala", MetadataNormalizer.normalizeSong("Da La！"))
        // 嵌套括号
        assertEquals("平凡之路", MetadataNormalizer.normalizeSong("平凡之路（电影《后会无期》主题曲(2014)）"))
    }

    @Test
    fun `繁体转简体`() {
        assertEquals("陈绮贞", MetadataNormalizer.normalizeSong("陳綺貞"))
        assertEquals("后来", MetadataNormalizer.normalizeSong("後來"))
        assertEquals("龙卷风", MetadataNormalizer.normalizeSong("龍捲風"))
    }

    @Test
    fun `歌手归一含别名映射与多艺人排序`() {
        val n = MetadataNormalizer
        assertEquals("周杰伦", n.normalizeArtist("Jay Chou"))
        assertEquals("周杰伦", n.normalizeArtist("周杰伦"))
        assertEquals("周杰伦", n.normalizeArtist("周杰伦&Jay Chou")) // 别名去重
        // 多艺人排序（按码点排序保证 A/B 与 B/A 同键）
        assertEquals("五月天/周杰伦", n.normalizeArtist("五月天、Jay Chou"))
        assertEquals(
            "五月天/周杰伦",
            n.normalizeArtist("Jay Chou、五月天"),
        ) // 顺序无关
        assertEquals("陈绮贞", n.normalizeArtist("陳綺貞"))
        assertEquals("邓紫棋", n.normalizeArtist("G.E.M.")) // 纯别名
    }

    @Test
    fun `归一键双模式`() {
        val n = MetadataNormalizer
        assertEquals("晴天::周杰伦", n.normalizedKey("晴天（Live）", "Jay Chou"))
        assertEquals("晴天::周杰伦", n.normalizedKey("晴 天", "周杰伦"))
        assertEquals("晴天", n.normalizedKey("晴天", "五月天", MetadataNormalizer.DedupMode.SONG_ONLY))
        // 模式①：同名不同歌手同键；模式②：不同键
        assertEquals(
            n.normalizedKey("晴天", "五月天", MetadataNormalizer.DedupMode.SONG_ONLY),
            n.normalizedKey("晴天", "周杰伦", MetadataNormalizer.DedupMode.SONG_ONLY),
        )
        assertTrue(
            n.normalizedKey("晴天", "五月天") != n.normalizedKey("晴天", "周杰伦"),
        )
    }

    @Test
    fun `levenshtein 距离`() {
        val n = MetadataNormalizer
        assertEquals(0, n.levenshtein("陈绮贞", "陈绮贞"))
        assertEquals(1, n.levenshtein("陈绮贞", "陈绮貞")) // 单字差异
        assertEquals(3, n.levenshtein("陈绮贞", "陳綺貞")) // 全不同（需先繁简归一再比较）
        assertEquals(2, n.levenshtein("abc", "axcx"))
        assertEquals(3, n.levenshtein("kitten", "sitting")) // 经典用例，距离>1 不并
    }

    @Test
    fun `版本词表命中分类`() {
        val v = VersionKeywords
        assertEquals(setOf(v.CAT_LIVE), v.matchedCategories("晴天 Live"))
        assertEquals(setOf(v.CAT_INSTRUMENTAL), v.matchedCategories("晴天伴奏"))
        assertEquals(setOf(v.CAT_COVER), v.matchedCategories("晴天 Cover by someone"))
        assertEquals(setOf(v.CAT_REMIX, v.CAT_SPEED), v.matchedCategories("晴天 Slowed Reverb Remix"))
        assertEquals(emptySet<Int>(), v.matchedCategories("晴天"))
    }
}
