package com.yinyue.domain.verify

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalityVerifierTest {

    private val neutral = OriginalityVerifier.SignalA()

    @Test
    fun `阈值三档边界`() {
        assertEquals(OriginalityVerifier.State.ORIGINAL, OriginalityVerifier.toState(0.70f))
        assertEquals(OriginalityVerifier.State.PENDING, OriginalityVerifier.toState(0.69f))
        assertEquals(OriginalityVerifier.State.PENDING, OriginalityVerifier.toState(0.40f))
        assertEquals(OriginalityVerifier.State.SUSPECT_COVER, OriginalityVerifier.toState(0.39f))
    }

    @Test
    fun `B站官方与时长吻合强信号`() {
        val r = OriginalityVerifier.fuse(
            neutral,
            OriginalityVerifier.SignalB(matched = true, official = true, durationMatch = true),
        )
        assertEquals(OriginalityVerifier.State.ORIGINAL, r.state) // 0.5+0.4+0.1 = 1.0
    }

    @Test
    fun `B站翻唱冲突降权`() {
        val r = OriginalityVerifier.fuse(
            OriginalityVerifier.SignalA(platformTagOriginal = false),
            OriginalityVerifier.SignalB(matched = true, coverConflict = true),
        )
        assertEquals(OriginalityVerifier.State.SUSPECT_COVER, r.state) // 0.5-0.3-0.3 = 0（封底）
    }

    @Test
    fun `无B站匹配则中性`() {
        val r = OriginalityVerifier.fuse(neutral, OriginalityVerifier.SignalB(matched = false))
        assertEquals(0.5f, r.score)
        assertEquals(OriginalityVerifier.State.PENDING, r.state)
    }

    @Test
    fun `平台原唱标签加权`() {
        val r = OriginalityVerifier.fuse(
            OriginalityVerifier.SignalA(platformTagOriginal = true),
            OriginalityVerifier.SignalB(matched = false),
        )
        assertEquals(0.85f, r.score)
        assertEquals(OriginalityVerifier.State.ORIGINAL, r.state)
    }
}
