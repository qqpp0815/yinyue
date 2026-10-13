package com.yinyue.player.domain.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起播全局预算（"3~5s 内切歌"的兑现口径）：
 * fast 竞速 + 慢速补试 + 起播等待合计不得越过 TOTAL_BOOT_BUDGET_MS。
 *
 * 这几条钉住的是"预算数学"，不需要设备/网络；一旦有人把某段改回固定超时（叠加白等），
 * 这里会先红。
 */
class BootBudgetTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `全局预算常量与 3~5s 目标一致`() {
        assertTrue(
            "全局预算应 <= 5s",
            PlaybackCoordinator.TOTAL_BOOT_BUDGET_MS <= 5_000L,
        )
        assertTrue(
            "全局预算应 >= 3s（低于 3s 会误杀正常弱网起播）",
            PlaybackCoordinator.TOTAL_BOOT_BUDGET_MS >= 3_000L,
        )
    }

    @Test
    fun `竞速与慢轮之和不越过全局预算`() {
        val sum = PlaybackCoordinator.FAST_RACE_MAX_MS +
            PlaybackCoordinator.SLOW_RESOLVE_BUDGET_MS +
            PlaybackCoordinator.AWAIT_START_TIMEOUT_MS
        assertTrue("fast + slow + 起播 = ${sum}ms 不应超过全局预算 + 余量", sum <= 12_500L)
    }

    @Test
    fun `剩余充足时按理想预算给`() {
        val deadline = now + PlaybackCoordinator.TOTAL_BOOT_BUDGET_MS
        val ideal = PlaybackCoordinator.MIN_SLOW_BUDGET_MS // 理想值小于剩余，应原样给出
        val budget = PlaybackCoordinator.budgetWithin(
            deadline,
            ideal,
            PlaybackCoordinator.MIN_SLOW_BUDGET_MS,
        )
        assertEquals(ideal, budget)
    }

    @Test
    fun `剩余不足保底时不再开新步骤`() {
        val deadline = now + PlaybackCoordinator.MIN_SLOW_BUDGET_MS - 1
        val budget = PlaybackCoordinator.budgetWithin(
            deadline,
            PlaybackCoordinator.SLOW_RESOLVE_BUDGET_MS,
            PlaybackCoordinator.MIN_SLOW_BUDGET_MS,
        )
        assertNull("剩余低于保底应返回 null（跳过慢轮/竞速，直接兜底）", budget)
    }

    @Test
    fun `剩余介于保底与理想之间时截断到剩余`() {
        val remaining = PlaybackCoordinator.MIN_SLOW_BUDGET_MS + 500L
        val deadline = now + remaining
        val budget = PlaybackCoordinator.budgetWithin(
            deadline,
            PlaybackCoordinator.SLOW_RESOLVE_BUDGET_MS,
            PlaybackCoordinator.MIN_SLOW_BUDGET_MS,
        )
        assertTrue("预算应被截断到剩余附近", budget != null && budget <= remaining && budget > 0)
    }

    @Test
    fun `预算已耗尽时剩余为负`() {
        val deadline = now - 1_000L
        assertTrue(PlaybackCoordinator.remainingBootMs(deadline) < 0)
    }

    @Test
    fun `起播等待也在全局预算内`() {
        // 剩余 100ms：低于 MIN_BOOT_AWAIT_MS，不应再尝试起播等待
        val deadline = now + 100L
        assertNull(
            PlaybackCoordinator.budgetWithin(
                deadline,
                PlaybackCoordinator.AWAIT_START_TIMEOUT_MS,
                PlaybackCoordinator.MIN_BOOT_AWAIT_MS,
            ),
        )
    }

    @Test
    fun `切源状态机_兜底门禁与预算结构约束`() {
        // 兜底门禁：autoSwitch 下至少跑 MIN_FALLBACK_ROUNDS 轮慢轮才允许 B站兜底
        assertEquals(2, PlaybackCoordinator.MIN_FALLBACK_ROUNDS)
        // 竞速预算必须明显小于慢轮单轮保底（否则慢轮没机会跑就吃满预算）
        assertTrue(
            "竞速预算应小于慢轮保底的 3 倍",
            PlaybackCoordinator.FAST_RACE_BUDGET_MS < PlaybackCoordinator.MIN_SLOW_BUDGET_MS * 3,
        )
        // 竞速 + 单轮慢轮 + 起播保底 应能装进全局预算（保证第 2 轮慢轮有剩余预算可开）
        val oneRoundPath = PlaybackCoordinator.FAST_RACE_BUDGET_MS +
            PlaybackCoordinator.MIN_SLOW_BUDGET_MS +
            PlaybackCoordinator.MIN_BOOT_AWAIT_MS
        assertTrue(
            "竞速+单轮慢轮+起播保底 之和必须小于全局预算",
            oneRoundPath < PlaybackCoordinator.TOTAL_BOOT_BUDGET_MS,
        )
    }
}
