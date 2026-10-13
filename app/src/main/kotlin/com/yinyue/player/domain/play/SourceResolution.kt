package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.MusicEntity

/**
 * 音源解析编排 module（候选 1 第 2 步）：
 * 把「快速竞速 / 慢速补试」的调用编排从 PlaybackCoordinator 收进来，
 * 对外只暴露两个意图接口，PlaybackCoordinator 不再直接碰 [MusicUrlResolver] 的两种竞速入口。
 *
 * interface 只回答「有没有可播源、按什么顺序试」；
 * 解析、探测、锁链、EXPIRED 写入等实现细节仍在 resolver 内部。
 */
object SourceResolution {

    /** 一次「链接 → 可播直链」的尝试结果 */
    data class Attempt(val link: AudioLink, val resolved: MusicUrlResolver.Resolved)

    /** 慢速补试结果：本轮可播列表 + 已出结论（含失败）的链接 id */
    data class SlowOutcome(val playable: List<Attempt>, val finished: Set<Long>)

    /** 快速竞速：并行解析+探测，取最先可播；无则 null */
    suspend fun fastRace(
        context: Context,
        links: List<AudioLink>,
        music: MusicEntity?,
        budgetMs: Long = PlaybackCoordinator.FAST_RACE_BUDGET_MS,
        batchSize: Int = PlaybackCoordinator.MAX_REMOTE_ATTEMPTS,
    ): Attempt? {
        val winner = MusicUrlResolver.get(context).resolveFirstPlayable(links, music, batchSize, budgetMs)
            ?: return null
        return Attempt(winner.first, winner.second)
    }

    /** 慢速补试：并行全量检测，按就绪先后返回可播列表 */
    suspend fun slowRace(
        context: Context,
        links: List<AudioLink>,
        music: MusicEntity?,
        budgetMs: Long = PlaybackCoordinator.SLOW_RESOLVE_BUDGET_MS,
    ): SlowOutcome {
        val race = MusicUrlResolver.get(context).resolveAllPlayable(links, music, budgetMs)
        return SlowOutcome(
            playable = race.winners.map { Attempt(it.first, it.second) },
            finished = race.finished,
        )
    }

    /** 单链解析（手动切源用） */
    suspend fun resolveOne(
        context: Context,
        link: AudioLink,
        music: MusicEntity?,
    ): MusicUrlResolver.Resolved? = MusicUrlResolver.get(context).resolve(link, music)
}