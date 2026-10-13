package com.yinyue.player.data.source.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * bestQuality 必须按音质 level 比较，不能按枚举序号。
 * 枚举声明顺序是高→低（FLAC24BIT/FALC/K320/K128/UNKNOWN），序号与音质相反——
 * 曾用 maxOfOrNull { it.type } 导致所有链接都标 128K、取链也只试 128K。
 */
class RawTrackQualityTest {

    private fun track(vararg q: QualityType) = RawTrack(
        platform = "kw",
        remoteTrackId = "1",
        songName = "晴天",
        artistName = "周杰伦",
        qualitys = q.map { TrackQuality(it) },
    )

    @Test
    fun `多档位时取最高档而不是序号最大`() {
        assertEquals(
            QualityType.FLAC,
            track(QualityType.K128, QualityType.K320, QualityType.FLAC).bestQuality,
        )
        assertEquals(
            QualityType.FLAC24BIT,
            track(QualityType.K128, QualityType.FLAC24BIT, QualityType.FLAC).bestQuality,
        )
        assertEquals(
            QualityType.K320,
            track(QualityType.K128, QualityType.K320).bestQuality,
        )
    }

    @Test
    fun `存在 UNKNOWN 时不能被它顶掉`() {
        assertEquals(
            QualityType.K320,
            track(QualityType.UNKNOWN, QualityType.K320, QualityType.K128).bestQuality,
        )
    }

    @Test
    fun `只有128k时结果就是128k`() {
        assertEquals(QualityType.K128, track(QualityType.K128).bestQuality)
    }

    @Test
    fun `无音质信息时为 UNKNOWN`() {
        assertEquals(QualityType.UNKNOWN, track().bestQuality)
    }
}
