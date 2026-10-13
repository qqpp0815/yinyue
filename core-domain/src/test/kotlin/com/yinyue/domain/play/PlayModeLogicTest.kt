package com.yinyue.domain.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切歌模式：五种模式的行为对齐落雪 playNext 的 switch */
class PlayModeLogicTest {

    @Test
    fun `列表循环到底回头`() {
        assertEquals(1, PlayModeLogic.nextIndex(0, 3, PlayMode.LIST_LOOP))
        assertEquals(2, PlayModeLogic.nextIndex(1, 3, PlayMode.LIST_LOOP))
        assertEquals(0, PlayModeLogic.nextIndex(2, 3, PlayMode.LIST_LOOP))
        assertEquals(0, PlayModeLogic.nextIndex(-1, 3, PlayMode.LIST_LOOP))
    }

    @Test
    fun `顺序播放到底停止`() {
        assertEquals(1, PlayModeLogic.nextIndex(0, 3, PlayMode.LIST))
        assertEquals(2, PlayModeLogic.nextIndex(1, 3, PlayMode.LIST))
        assertNull("最后一首之后不再切", PlayModeLogic.nextIndex(2, 3, PlayMode.LIST))
    }

    @Test
    fun `单曲循环下一首仍是当前`() {
        assertEquals(1, PlayModeLogic.nextIndex(1, 3, PlayMode.SINGLE_LOOP))
        assertEquals(2, PlayModeLogic.nextIndex(2, 3, PlayMode.SINGLE_LOOP))
    }

    @Test
    fun `不切换返回空`() {
        assertNull(PlayModeLogic.nextIndex(1, 3, PlayMode.NONE))
        assertNull(PlayModeLogic.previousIndex(1, 3, PlayMode.NONE))
        assertFalse(PlayModeLogic.autoAdvance(PlayMode.NONE))
        assertTrue(PlayModeLogic.autoAdvance(PlayMode.LIST_LOOP))
    }

    @Test
    fun `随机在范围内`() {
        assertEquals(2, PlayModeLogic.nextIndex(0, 5, PlayMode.RANDOM) { 2 })
        // 注入越界值也要被收敛进范围
        assertEquals(4, PlayModeLogic.nextIndex(0, 5, PlayMode.RANDOM) { 99 })
        assertEquals(1, PlayModeLogic.nextIndex(0, 5, PlayMode.RANDOM) { -3 })
        assertEquals("随机下一首不重复当前歌", 2, PlayModeLogic.nextIndex(1, 5, PlayMode.RANDOM) { 1 })
        assertEquals("随机循环回另一首", 0, PlayModeLogic.nextIndex(1, 2, PlayMode.RANDOM) { 1 })
    }

    @Test
    fun `上一首在列表循环里回头 顺序播放里不回头`() {
        assertEquals(2, PlayModeLogic.previousIndex(0, 3, PlayMode.LIST_LOOP))
        assertEquals(1, PlayModeLogic.previousIndex(2, 3, PlayMode.LIST_LOOP))
        assertNull("顺序播放第一首之前不再切", PlayModeLogic.previousIndex(0, 3, PlayMode.LIST))
        assertEquals(1, PlayModeLogic.previousIndex(2, 3, PlayMode.LIST))
    }

    @Test
    fun `空队列不切歌`() {
        PlayMode.entries.forEach { mode ->
            assertNull(PlayModeLogic.nextIndex(0, 0, mode))
            assertNull(PlayModeLogic.previousIndex(0, 0, mode))
        }
    }

    @Test
    fun `模式名与显示名`() {
        assertEquals(PlayMode.LIST_LOOP, PlayMode.fromName("listLoop".let { "LIST_LOOP" }))
        assertEquals(PlayMode.DEFAULT, PlayMode.fromName("bogus"))
        assertEquals("列表循环", PlayModeLogic.label(PlayMode.LIST_LOOP))
        assertEquals("随机播放", PlayModeLogic.label(PlayMode.RANDOM))
        assertEquals("顺序播放", PlayModeLogic.label(PlayMode.LIST))
        assertEquals("单曲循环", PlayModeLogic.label(PlayMode.SINGLE_LOOP))
        assertEquals("不切换", PlayModeLogic.label(PlayMode.NONE))
    }
}
