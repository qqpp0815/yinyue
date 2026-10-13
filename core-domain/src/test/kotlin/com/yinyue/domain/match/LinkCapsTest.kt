package com.yinyue.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并限量：原唱全留、翻唱限量（用户要求"以原唱为主"） */
class LinkCapsTest {

    private fun item(id: Long, artist: String, score: Int = 20) = LinkCaps.Item(id, artist, score)

    @Test
    fun `原唱的链接全部保留`() {
        val items = (1L..20L).map { item(it, "梁静茹") }
        val keep = LinkCaps.keep(items, "梁静茹")
        assertEquals("同歌手的不同版本都应保留", 20, keep.size)
    }

    @Test
    fun `他人翻唱限量保留`() {
        val original = (1L..5L).map { item(it, "梁静茹") }
        val covers = (100L..130L).map { item(it, "某某翻唱") } // 31 条翻唱
        val keep = LinkCaps.keep(original + covers, "梁静茹")
        assertEquals("原唱 5 条全留 + 翻唱最多 8 条", 13, keep.size)
        assertTrue("原唱必须全在", original.all { it.id in keep })
        assertEquals("翻唱只留 8 条", 8, covers.count { it.id in keep })
    }

    @Test
    fun `翻唱按优先级分挑高的留下`() {
        val original = listOf(item(1, "梁静茹"))
        val covers = listOf(
            item(101, "翻唱A", score = 10),
            item(102, "翻唱B", score = 40),
            item(103, "翻唱C", score = 30),
        )
        val keep = LinkCaps.keep(original + covers, "梁静茹", maxCovers = 2)
        assertTrue("最高分翻唱（40）应留下", 102L in keep)
        assertTrue("次高分翻唱（30）应留下", 103L in keep)
        assertFalse("最低分翻唱（10）应被裁掉", 101L in keep)
    }

    @Test
    fun `歌手未知时不误删`() {
        val items = (1L..30L).map { item(it, "") } // artistKey 为空 = 未知
        val keep = LinkCaps.keep(items, "梁静茹")
        assertEquals(30, keep.size)
    }

    @Test
    fun `总量上限优先砍翻唱`() {
        val original = (1L..50L).map { item(it, "梁静茹") }
        val covers = (100L..140L).map { item(it, "翻唱", score = 99) }
        val keep = LinkCaps.keep(original + covers, "梁静茹", maxTotal = 55, maxCovers = 40)
        assertEquals(55, keep.size)
        assertEquals("原唱 50 条全留", 50, original.count { it.id in keep })
        assertEquals("剩下的名额给翻唱", 5, covers.count { it.id in keep })
    }
}
