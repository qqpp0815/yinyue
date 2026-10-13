package com.yinyue.domain.play

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「播放失败自动跳下一首」的防环决策（用户要求：切源不失败 + 自动播放下一首） */
class AutoSkipGuardTest {

    @Test
    fun `正常失败时自动跳下一首`() {
        assertTrue(AutoSkipGuard.shouldSkip(0, queueSize = 10))
        assertTrue(AutoSkipGuard.shouldSkip(1, queueSize = 3))
    }

    @Test
    fun `连续失败达熔断线后停下`() {
        assertFalse("连跳 5 首都失败，第 6 首不再自动跳（防无限环）", AutoSkipGuard.shouldSkip(5, queueSize = 20))
        assertFalse(AutoSkipGuard.shouldSkip(6, queueSize = 20))
    }

    @Test
    fun `队列只有一首时不跳`() {
        // 跳了还是自己 = 无间隔无限重试自己，交给 SourceSession 后台自愈
        assertFalse(AutoSkipGuard.shouldSkip(0, queueSize = 1))
        assertFalse(AutoSkipGuard.shouldSkip(0, queueSize = 0))
    }

    @Test
    fun `熔断线内但队列足够长则继续跳`() {
        assertTrue(AutoSkipGuard.shouldSkip(4, queueSize = 2))
        assertFalse(AutoSkipGuard.shouldSkip(5, queueSize = 2))
    }
}
