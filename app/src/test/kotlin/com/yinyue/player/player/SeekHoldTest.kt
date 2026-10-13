package com.yinyue.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度条 seek 回落保护（用户报 bug 的回归测试）：
 * 「拖动进度条，会恢复原来位置然后再回到拖动点」。
 *
 * 原因：seek 异步，松手后播放器仍会先回报**旧位置**；
 * 若直接采纳，进度条先弹回原处、等播放器到位后再跳回拖动点。
 */
class SeekHoldTest {

    @Test
    fun `seeking 后播放器还没到位时_展示本地目标位置而不是旧位置`() {
        val hold = SeekHold()
        hold.onSeek(target = 120_000, nowMs = 1_000)
        // 播放器仍回报旧位置 60s（还没跳过去）
        assertEquals(120_000L, hold.displayPosition(playerPositionMs = 60_000, nowMs = 1_100))
        assertEquals(120_000L, hold.displayPosition(playerPositionMs = 60_000, nowMs = 1_500))
    }

    @Test
    fun `播放器到位后_交回播放器位置`() {
        val hold = SeekHold()
        hold.onSeek(target = 120_000, nowMs = 1_000)
        // 回到 119.5s：已在容差内 → 认为到位，交回播放器位置（之后跟着正常走）
        assertNull(hold.displayPosition(playerPositionMs = 119_500, nowMs = 1_600))
    }

    @Test
    fun `超时后不再保护_避免卡在目标位置`() {
        val hold = SeekHold()
        hold.onSeek(target = 120_000, nowMs = 1_000)
        // 超过保护窗口：即使播放器位置差很远也交回（如 seek 失败/被忽略）
        assertNull(hold.displayPosition(playerPositionMs = 60_000, nowMs = 2_500))
    }

    @Test
    fun `没有 seek 时不干预播放位置`() {
        val hold = SeekHold()
        assertNull(hold.displayPosition(playerPositionMs = 60_000, nowMs = 1_000))
    }

    @Test
    fun `切歌会清掉 seek 保护`() {
        val hold = SeekHold()
        hold.onSeek(target = 120_000, nowMs = 1_000)
        hold.reset()
        assertNull(hold.displayPosition(playerPositionMs = 0, nowMs = 1_050))
        assertEquals(-1L, hold.targetMs)
    }

    @Test
    fun `负目标位置被规范化到 0`() {
        val hold = SeekHold()
        hold.onSeek(target = -5, nowMs = 1_000)
        assertEquals(0L, hold.targetMs)
    }
}
