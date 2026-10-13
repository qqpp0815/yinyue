package com.yinyue.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 去重核心算法测试：对齐 lx findMusic 的可观测行为 */
class SongMatcherTest {

    private fun key(name: String, singer: String, album: String? = null, intervalSec: Int = 269) =
        SongMatcher.Key.of(name, singer, album, intervalSec)

    @Test
    fun `filterStr 字符集与落雪一致`() {
        assertEquals("晴天", SongMatcher.filterStr("晴 天"))
        // filterStr 不做大小写（落雪由调用方 toLowerCase）
        assertEquals("JayChou", SongMatcher.filterStr("Jay-Chou"))
        assertEquals("abc", SongMatcher.filterStr("a(b)c"))
        assertEquals("ab", SongMatcher.filterStr("a、b"))
        assertEquals("test", SongMatcher.filterStr("t'e.s,t"))
        assertEquals("xy", SongMatcher.filterStr("x<y>"))
    }

    @Test
    fun `sortSingle 多歌手拆分排序归一`() {
        // 排序与落雪 localeCompare 一致（中文按拼音序：五月天 < 周杰伦）
        assertEquals("五月天、周杰伦", SongMatcher.sortSingle("五月天、周杰伦"))
        assertEquals("五月天、周杰伦", SongMatcher.sortSingle("周杰伦、五月天"))
        assertEquals("五月天、周杰伦", SongMatcher.sortSingle("周杰伦/五月天"))
        assertEquals("a、b、c", SongMatcher.sortSingle("c&a;b"))
        assertEquals("周杰伦", SongMatcher.sortSingle("周杰伦"))
    }

    @Test
    fun `时长解析与容差`() {
        assertEquals(269, SongMatcher.parseIntv("4:29"))
        assertEquals(3725, SongMatcher.parseIntv("1:02:05"))
        assertEquals(0, SongMatcher.parseIntv(null))
        assertTrue(SongMatcher.isEqualsInterval(269, 274)) // +5s 边界内
        assertTrue(SongMatcher.isEqualsInterval(269, 264)) // -5s 边界内
        assertFalse(SongMatcher.isEqualsInterval(269, 275)) // 超出
    }

    @Test
    fun `版本词一致性：Live版不与录音室版合并`() {
        // 目标"晴天"，候选"晴天 (Live)"——filterStr 后含 live，版本词不一致
        val a = key("晴天", "周杰伦")
        val b = key("晴天 (Live)", "周杰伦")
        assertFalse(SongMatcher.isSameSong(a, b))
        // 双方都含 live 则一致
        val a2 = key("晴天 Live", "周杰伦")
        val b2 = key("晴天 (Live版)", "周杰伦")
        assertTrue(SongMatcher.isSameSong(a2, b2))
    }

    @Test
    fun `同名不同歌手不合并（歌手不匹配）`() {
        val a = key("晴天", "周杰伦")
        val b = key("晴天", "五月天")
        assertFalse(SongMatcher.isSameSong(a, b))
    }

    @Test
    fun `歌手写法差异可合并（双向包含）`() {
        val a = key("晴天", "周杰伦")
        val b = key("晴天", "周杰伦 Jay Chou") // 归一后含关系
        assertTrue(SongMatcher.isSameSong(a, b))
    }

    @Test
    fun `时长差异超容差不合并`() {
        val a = key("晴天", "周杰伦", intervalSec = 269)
        val b = key("晴天", "周杰伦", intervalSec = 300)
        assertFalse(SongMatcher.isSameSong(a, b))
    }

    @Test
    fun `groupSongs 跨平台合并同一首歌`() {
        data class T(val platform: String, val name: String, val singer: String, val intv: Int)
        val tracks = listOf(
            T("kw", "晴天", "周杰伦", 269),
            T("wy", "晴天", "周杰伦", 269),
            T("kg", "晴天", "周杰伦", 270),
            T("tx", "晴天 (Live)", "周杰伦", 300), // 版本词不同 → 独立组
            T("mg", "晴天", "五月天", 269),        // 歌手不同 → 独立组
        )
        val groups = SongMatcher.groupSongs(tracks) { t -> key(t.name, t.singer, null, t.intv) }
        assertEquals(3, groups.size)
        assertEquals(3, groups[0].size) // kw/wy/kg 合并
        assertEquals(1, groups[1].size)
        assertEquals(1, groups[2].size)
    }

    @Test
    fun `rankByCloseness 九级优先排序`() {
        data class T(val tag: String, val name: String, val singer: String, val album: String?, val intv: Int)
        val target = key("晴天", "周杰伦", "叶惠美", 269)
        val candidates = listOf(
            T("weak", "晴天", "五月天", null, 300),              // 最弱：仅部分匹配
            T("perfect", "晴天", "周杰伦", "叶惠美", 269),        // 最强
            T("nameOnly", "晴天", "五月天", null, 269),           // 歌名+时长
            T("singerName", "晴天", "周杰伦", null, 300),         // 歌名+歌手
        )
        val ranked = SongMatcher.rankByCloseness(target, candidates) { t ->
            key(t.name, t.singer, t.album, t.intv)
        }
        assertEquals("perfect", ranked[0].tag)
        assertEquals("singerName", ranked[1].tag)
        assertEquals("nameOnly", ranked[2].tag)
        assertEquals("weak", ranked[3].tag)
    }
}
