package com.yinyue.player.domain.play

import com.yinyue.domain.play.PlayMode
import com.yinyue.domain.play.PlayModeLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放队列下标同步（用户报的两个 bug 的回归测试）：
 *  ①「当前第 4 首，点第 10 首，第 10 首播完却跳去第 5 首，而不是第 11 首」；
 *  ②「播放列表里高亮位置也不对」。
 *
 * 根因：从「播放列表弹层 / 我的-播放列表」点条目时只调了 `play(musicId)`，
 * **没有同步队列下标**，于是下标还停在第 4 首 → 播完按旧下标续播；高亮同理。
 * 修复：`PlaybackCoordinator.play/playMv` 内部统一调用 syncIndexTo*。
 */
class PlaybackQueueIndexTest {

    private fun audios(count: Int): List<PlaybackQueue.Item> =
        (1..count).map { PlaybackQueue.Item.Audio(it.toLong(), "歌曲$it", "歌手") }

    @Test
    fun `点第10首后下标对齐_播完续播走第11首`() {
        PlaybackQueue.clear()
        val items = audios(20)
        PlaybackQueue.playList(items, 3) // 当前第 4 首
        assertEquals(3, PlaybackQueue.currentIndex.value)

        // 播放列表里点第 10 首（修复点：下标必须跟到 9）
        assertEquals(true, PlaybackQueue.syncIndexToAudio(10L))
        assertEquals(9, PlaybackQueue.currentIndex.value)

        // 播完自动续播（列表循环）：应当走第 11 首
        val next = PlayModeLogic.nextIndex(PlaybackQueue.currentIndex.value, items.size, PlayMode.LIST_LOOP)
        assertEquals(10, next)
        assertEquals("歌曲11", PlaybackQueue.jumpTo(next!!)?.title)

        PlaybackQueue.clear()
    }

    @Test
    fun `未同步下标时续播会跳错歌_说明修复必要性`() {
        PlaybackQueue.clear()
        val items = audios(20)
        PlaybackQueue.playList(items, 3)
        // 模拟修复前的行为：点了第 10 首但下标仍是 3
        val next = PlayModeLogic.nextIndex(PlaybackQueue.currentIndex.value, items.size, PlayMode.LIST_LOOP)
        assertEquals("歌曲5", PlaybackQueue.jumpTo(next!!)?.title) // 正是用户报的错误结果
        PlaybackQueue.clear()
    }

    @Test
    fun `下标已指向该歌时不动_同一首歌出现多次也尊重用户点的那一项`() {
        PlaybackQueue.clear()
        val items = listOf(
            PlaybackQueue.Item.Audio(7L, "重复", "歌手"),
            PlaybackQueue.Item.Audio(1L, "别的", "歌手"),
            PlaybackQueue.Item.Audio(7L, "重复", "歌手"),
        )
        PlaybackQueue.playList(items, 2) // 用户点的是第 3 项（重复曲的第 2 次出现）
        assertEquals(false, PlaybackQueue.syncIndexToAudio(7L)) // 下标已对 → 不算变更
        assertEquals(2, PlaybackQueue.currentIndex.value)
        PlaybackQueue.clear()
    }

    @Test
    fun `不在队列中则保持原下标`() {
        PlaybackQueue.clear()
        PlaybackQueue.playList(audios(5), 2)
        assertEquals(false, PlaybackQueue.syncIndexToAudio(999L))
        assertEquals(2, PlaybackQueue.currentIndex.value)
        PlaybackQueue.clear()
    }

    @Test
    fun `MV 项按 bvid 对齐`() {
        PlaybackQueue.clear()
        val items = listOf(
            PlaybackQueue.Item.Mv("BV1", "MV1", "UP"),
            PlaybackQueue.Item.Mv("BV2", "MV2", "UP"),
            PlaybackQueue.Item.Mv("BV3", "MV3", "UP"),
        )
        PlaybackQueue.playList(items, 0)
        assertEquals(true, PlaybackQueue.syncIndexToMv("BV3"))
        assertEquals(2, PlaybackQueue.currentIndex.value)
        PlaybackQueue.clear()
    }

    @Test
    fun `空队列同步下标不报错且下标保持-1`() {
        PlaybackQueue.clear()
        assertEquals(false, PlaybackQueue.syncIndexToAudio(1L))
        assertEquals(-1, PlaybackQueue.currentIndex.value)
        assertNull(PlaybackQueue.current)
    }
}

