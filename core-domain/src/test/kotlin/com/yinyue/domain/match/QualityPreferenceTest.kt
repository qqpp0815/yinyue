package com.yinyue.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 优先播放音质：只向下兼容、不向上越档（对齐落雪 getPlayQuality） */
class QualityPreferenceTest {

    @Test
    fun `默认档位与落雪一致是128k`() {
        assertEquals("128k", QualityPreference.DEFAULT)
        assertEquals(listOf("128k"), QualityPreference.chain(QualityPreference.DEFAULT))
    }

    @Test
    fun `选320k只试320k与128k`() {
        assertEquals(listOf("320k", "128k"), QualityPreference.chain(QualityPreference.K320))
    }

    @Test
    fun `选无损会向下兼容`() {
        assertEquals(
            listOf("flac", "320k", "128k"),
            QualityPreference.chain(QualityPreference.FLAC),
        )
    }

    @Test
    fun `选无损24bit先要最高档再逐级降`() {
        assertEquals(
            listOf("flac24bit", "flac", "320k", "128k"),
            QualityPreference.chain(QualityPreference.FLAC24BIT),
        )
    }

    @Test
    fun `未知档位回落到默认`() {
        assertEquals(listOf("128k"), QualityPreference.chain("wav"))
    }

    @Test
    fun `档位显示名与优先级分`() {
        assertEquals("无损 24bit", QualityPreference.label(QualityPreference.FLAC24BIT))
        assertEquals("无损", QualityPreference.label(QualityPreference.FLAC))
        assertEquals("320K", QualityPreference.label(QualityPreference.K320))
        assertEquals("128K", QualityPreference.label(QualityPreference.K128))
        assertEquals(4, QualityPreference.levelOf(QualityPreference.FLAC))
        assertEquals(3, QualityPreference.levelOf(QualityPreference.K320))
        assertEquals(2, QualityPreference.levelOf(QualityPreference.K128))
    }

    /** 自动档：不设上限，从最高档往下找（用户反馈"选 128K 就永远拿不到更高音质"的解法） */
    @Test
    fun `自动档位从最高档往下找`() {
        assertEquals(
            listOf("flac24bit", "flac", "320k", "128k"),
            QualityPreference.chain(QualityPreference.AUTO),
        )
        assertTrue(QualityPreference.CHOICES.contains(QualityPreference.AUTO))
        assertEquals("自动（尽可能高）", QualityPreference.label(QualityPreference.AUTO))
    }
}