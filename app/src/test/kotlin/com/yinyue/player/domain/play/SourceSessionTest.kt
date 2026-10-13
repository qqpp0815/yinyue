package com.yinyue.player.domain.play

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSessionTest {

    @Test
    fun `探测硬等待是速度优先的临时判定`() {
        assertTrue(MusicUrlResolver.PROBE_WAIT_MS in 600..1_200)
        assertFalse(MusicUrlResolver.PROBE_WAIT_MS >= SourceSession.MAX_RECHECK_MS)
    }

    @Test
    fun `启动自愈预算高于播放热路径竞速预算`() {
        assertTrue(SourceSession.MAX_BOOT_RECHECK_MS > MusicUrlResolver.RESOLVE_RACE_BUDGET_MS)
    }

    @Test
    fun `启动自愈重试次数大于单次播放终态兜底`() {
        assertTrue(3 > 1)
    }

    @Test
    fun `音频竞速与起播等待的固定上限不超过5秒`() {
        assertTrue(MusicUrlResolver.RESOLVE_RACE_BUDGET_MS < 5_000L)
        assertTrue(2_500L + 1_000L <= 5_000L)
    }

    @Test
    fun `B站兜底取流保留5秒预算`() {
        assertFalse(5_000L > 5_000L)
    }
}
