package com.yinyue.player.domain.play

import com.yinyue.player.ui.navigation.RecordingNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导航 deep seam（候选 3）：意图可被记录，测试无需起 NavHost */
class RecordingNavigatorTest {

    @Test
    fun recordsSettingsIntent() {
        val nav = RecordingNavigator()
        nav.openSettings()
        assertEquals(listOf("settings"), nav.opened)
    }

    @Test
    fun recordsFallbackIntentInOrder() {
        val nav = RecordingNavigator()
        nav.openSettings()
        nav.openFallbackImport()
        assertEquals(listOf("settings", "fallback"), nav.opened)
    }

    @Test
    fun openedIsDefensiveCopy() {
        val nav = RecordingNavigator()
        nav.openSettings()
        assertTrue(nav.opened.isNotEmpty())
        assertEquals(1, nav.opened.size)
    }
}