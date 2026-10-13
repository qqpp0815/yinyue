package com.yinyue.player.player

/**
 * 进度条 seek 的「回落保护」（纯逻辑，便于单测）。
 *
 * 用户报的 bug：「拖动进度条，会恢复原来位置然后再回到拖动点」。
 * 根因：seek 是异步的 —— 松手后播放器还会先回报**旧位置**，
 * 若进度条直接采纳这个旧位置，就会先弹回原处，等播放器真正到位后再跳回拖动点。
 *
 * 做法：记住本地 seek 目标与时间；在播放器到位（或超时）之前，
 * 一律展示本地目标位置。
 */
class SeekHold(
    /** 保护窗口：这段时间内忽略播放器回报的旧位置 */
    private val holdMs: Long = 1_200L,
    /** 判定「已到位」的容差：播放器位置与目标足够接近就算到位 */
    private val reachedMs: Long = 1_500L,
) {

    @Volatile
    var targetMs: Long = -1L
        private set

    @Volatile
    private var atMs: Long = 0L

    fun onSeek(target: Long, nowMs: Long) {
        targetMs = target.coerceAtLeast(0)
        atMs = nowMs
    }

    fun reset() {
        targetMs = -1L
    }

    /**
     * 播放器回报位置时调用。
     * @return 本次应展示的位置；null = 保护已结束，按播放器位置展示即可
     */
    fun displayPosition(playerPositionMs: Long, nowMs: Long): Long? {
        val target = targetMs
        if (target < 0) return null
        if (nowMs - atMs > holdMs || kotlin.math.abs(playerPositionMs - target) < reachedMs) {
            targetMs = -1L
            return null
        }
        return target
    }
}
