package com.yinyue.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原唱判定测试（按"歌名 - 演唱者"多平台交叉） */
class OriginalityVoterTest {

    private fun v(artist: String, platform: String) = OriginalityVoter.Vote(artist, platform)

    @Test
    fun `多平台一致的演唱者判为原唱`() {
        val votes = OriginalityVoter.tally(listOf(v("周杰伦", "kw"), v("周杰伦", "kg"), v("周杰伦", "mg")))
        assertEquals(PriorityScorer.ORIGINAL, OriginalityVoter.tagOf("周杰伦", votes))
        assertTrue(OriginalityVoter.hasTrustedOriginal(votes))
        assertEquals("3 个平台 = 3 票", 3, votes["周杰伦"])
    }

    @Test
    fun `同平台多版本只算一票，不得凑够原唱`() {
        // 酷我一次返回同名 5 条发行（实测 刘若英《后来》即如此）：仍只是 1 个平台
        val votes = OriginalityVoter.tally(List(5) { v("周杰伦", "kw") })
        assertEquals(1, votes["周杰伦"])
        assertEquals(PriorityScorer.UNKNOWN_ORIGIN, OriginalityVoter.tagOf("周杰伦", votes))
        assertFalse("单平台不轻易判原唱", OriginalityVoter.hasTrustedOriginal(votes))
    }

    @Test
    fun `翻唱堆版本不能盖过原唱`() {
        // 某翻唱歌手单平台 3 条版本 vs 原唱 2 个平台各 1 条
        val votes = OriginalityVoter.tally(
            listOf(
                v("某翻唱", "kw"), v("某翻唱", "kw"), v("某翻唱", "kw"),
                v("原唱歌手", "kg"), v("原唱歌手", "mg"),
            ),
        )
        assertEquals(PriorityScorer.ORIGINAL, OriginalityVoter.tagOf("原唱歌手", votes))
        assertEquals(PriorityScorer.COVER, OriginalityVoter.tagOf("某翻唱", votes))
    }

    @Test
    fun `组内少数派演唱者判为翻唱`() {
        val votes = OriginalityVoter.tally(
            listOf(v("周杰伦", "kw"), v("周杰伦", "kg"), v("周杰伦", "mg"), v("某某翻唱", "tx")),
        )
        assertEquals(PriorityScorer.ORIGINAL, OriginalityVoter.tagOf("周杰伦", votes))
        assertEquals(PriorityScorer.COVER, OriginalityVoter.tagOf("某某翻唱", votes))
    }

    @Test
    fun `单平台不判原唱`() {
        val votes = OriginalityVoter.tally(listOf(v("周杰伦", "kw")))
        assertEquals(PriorityScorer.UNKNOWN_ORIGIN, OriginalityVoter.tagOf("周杰伦", votes))
        assertFalse(OriginalityVoter.hasTrustedOriginal(votes))
    }

    @Test
    fun `歌手缺失不判定`() {
        val votes = OriginalityVoter.tally(listOf(v("周杰伦", "kw"), v("周杰伦", "kg"), v("", "mg")))
        assertEquals(PriorityScorer.UNKNOWN_ORIGIN, OriginalityVoter.tagOf("", votes))
        assertEquals("空歌手已被过滤", 1, votes.size)
        assertEquals(2, votes["周杰伦"])
    }

    @Test
    fun `原唱与翻唱得分差异体现在优先级排序`() {
        val votes = OriginalityVoter.tally(
            listOf(v("周杰伦", "kw"), v("周杰伦", "kg"), v("某翻唱", "tx")),
        )
        val originalScore = PriorityScorer.score(
            3, OriginalityVoter.tagOf("周杰伦", votes), sourceWeight = 1,
        )
        val coverScore = PriorityScorer.score(
            3, OriginalityVoter.tagOf("某翻唱", votes), sourceWeight = 1,
        )
        assertTrue("原唱应排在翻唱前", originalScore > coverScore)
    }
}
