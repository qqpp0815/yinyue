package com.yinyue.domain.play

/**
 * 「播放失败自动跳下一首」的决策（对齐落雪 `player.autoSkipOnError`，默认开）：
 * 当前歌把所有可用音源（音频候选 + B站音轨兜底）都真实试完仍失败时，
 * 自动切到队列下一首，而不是停在"全部失效"页 —— 用户要求"切源不失败 + 后台自动播放下一首"。
 *
 * 防环是硬约束：整张队列全失效时不能无限跳歌（每一跳都会打一轮脚本/B站请求，
 * 实测会触发平台限频与风控）。连续失败 [MAX_CONSECUTIVE_FAILS] 首后停下交给人处理；
 * 任何一首起播成功即清零计数（由协调器负责）。
 *
 * 队列只有一首（size ≤ 1）时不跳：跳了还是自己，等价于无间隔的无限重试自己，
 * 交给 SourceSession 后台复核自愈更合适。
 */
object AutoSkipGuard {

    /** 连续自动跳歌上限：全队列失效时的熔断线 */
    const val MAX_CONSECUTIVE_FAILS = 5

    /**
     * @param consecutiveFails 本次会话内**连续**自动跳过的歌数（起播成功时归零）
     * @param queueSize 当前播放队列长度
     * @return true = 自动切下一首；false = 停下并显示终态提示
     */
    fun shouldSkip(consecutiveFails: Int, queueSize: Int): Boolean =
        queueSize > 1 && consecutiveFails < MAX_CONSECUTIVE_FAILS
}
