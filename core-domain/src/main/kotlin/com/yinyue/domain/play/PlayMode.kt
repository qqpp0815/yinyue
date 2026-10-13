package com.yinyue.domain.play

import kotlin.random.Random

/**
 * 切歌模式（对齐落雪 `player.togglePlayMethod`，默认 listLoop）：
 *  - [LIST_LOOP]   列表循环：下一首到底回头
 *  - [RANDOM]      随机播放：随机挑一首
 *  - [LIST]        顺序播放：到底停止（不再切）
 *  - [SINGLE_LOOP] 单曲循环：播完重播当前（手动"下一首"仍然切歌）
 *  - [NONE]        不切换：播完即停，手动"下一首"也不切
 */
enum class PlayMode {
    LIST_LOOP,
    RANDOM,
    LIST,
    SINGLE_LOOP,
    NONE,
    ;

    companion object {
        val DEFAULT = LIST_LOOP

        fun fromName(name: String?): PlayMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 切歌索引计算（纯函数，便于单测；随机源可注入） */
object PlayModeLogic {

    /**
     * 下一首的索引；返回 null = 不切换（停止）。
     * @param current 当前索引（可能为 -1，表示还没开始）
     * @param size    队列长度
     * @param mode    切歌模式
     * @param randomPick 随机取数（默认真随机；单测注入固定值）
     */
    fun nextIndex(
        current: Int,
        size: Int,
        mode: PlayMode,
        randomPick: (Int) -> Int = { Random.nextInt(it) },
    ): Int? {
        if (size <= 0) return null
        return when (mode) {
            PlayMode.LIST_LOOP -> if (current < 0) 0 else (current + 1) % size
            PlayMode.RANDOM -> {
                val picked = randomPick(size).coerceIn(0, size - 1)
                if (size > 1 && picked == current) (picked + 1) % size else picked
            }
            PlayMode.LIST -> if (current >= size - 1) null else (current + 1).coerceAtLeast(0)
            PlayMode.SINGLE_LOOP -> if (current < 0) 0 else current
            PlayMode.NONE -> null
        }
    }

    /** 上一首的索引；返回 null = 不切换 */
    fun previousIndex(current: Int, size: Int, mode: PlayMode): Int? {
        if (size <= 0) return null
        if (mode == PlayMode.NONE) return null
        if (current <= 0) return if (mode == PlayMode.LIST) null else size - 1
        return current - 1
    }

    /** 播完一首之后是否要自动切歌 */
    fun autoAdvance(mode: PlayMode): Boolean = mode != PlayMode.NONE

    fun label(mode: PlayMode): String = when (mode) {
        PlayMode.LIST_LOOP -> "列表循环"
        PlayMode.RANDOM -> "随机播放"
        PlayMode.LIST -> "顺序播放"
        PlayMode.SINGLE_LOOP -> "单曲循环"
        PlayMode.NONE -> "不切换"
    }
}
