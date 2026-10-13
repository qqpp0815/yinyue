package com.yinyue.player.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheEvictorLogicTest {

    private fun span(key: String, len: Long, touch: Long) =
        CacheEvictorLogic.SpanInfo(key, len, touch)

    @Test
    fun `未超配额不淘汰`() {
        val spans = listOf(span("a", 100, 1), span("b", 100, 2))
        assertEquals(emptyList<String>(), CacheEvictorLogic.planEvictions(spans, emptySet(), 1000))
    }

    @Test
    fun `超配额从最旧开始淘汰`() {
        val spans = listOf(span("a", 100, 1), span("b", 100, 2), span("c", 100, 3))
        // 总 300，配额 250 → 淘汰 a 后剩 200 ≤ 250
        assertEquals(listOf("a"), CacheEvictorLogic.planEvictions(spans, emptySet(), 250))
        // 配额 150 → 需淘汰 a、b 后剩 100 ≤ 150
        assertEquals(listOf("a", "b"), CacheEvictorLogic.planEvictions(spans, emptySet(), 150))
    }

    @Test
    fun `收藏保护不参与淘汰`() {
        val spans = listOf(span("fav", 100, 1), span("b", 100, 2), span("c", 100, 3))
        // fav 受保护 → 淘汰 b 后剩 200 ≤ 250
        assertEquals(listOf("b"), CacheEvictorLogic.planEvictions(spans, setOf("fav"), 250))
        // 配额 150 → 跳过 fav，淘汰 b、c
        assertEquals(listOf("b", "c"), CacheEvictorLogic.planEvictions(spans, setOf("fav"), 150))
    }

    @Test
    fun `全部受保护且超配额则不淘汰任何项`() {
        val spans = listOf(span("a", 100, 1), span("b", 100, 2))
        assertEquals(emptyList<String>(), CacheEvictorLogic.planEvictions(spans, setOf("a", "b"), 50))
    }

    @Test
    fun `连续淘汰直到满足配额`() {
        val spans = listOf(span("a", 100, 1), span("b", 100, 2), span("c", 100, 3), span("d", 100, 4))
        assertEquals(listOf("a", "b"), CacheEvictorLogic.planEvictions(spans, emptySet(), 200))
    }
}
