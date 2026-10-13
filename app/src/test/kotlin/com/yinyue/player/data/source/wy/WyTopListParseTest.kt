package com.yinyue.player.data.source.wy

import com.google.gson.JsonParser
import com.yinyue.player.data.source.model.QualityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 榜单/歌单接口的曲目解析（字段与搜索接口不同，需单独锁住） */
class WyTopListParseTest {

    @Test
    fun `解析新接口形态的曲目`() {
        val json = """
            [
              {"id": 1974443814, "name": "海屿你", "dt": 295000,
               "ar": [{"id": 1, "name": "马也_Crabbit"}],
               "al": {"id": 99, "name": "海屿你", "picUrl": "https://p1.music.126.net/x.jpg"}},
              {"id": 1901371647, "name": "甲乙丙丁", "dt": 200000,
               "ar": [{"id": 2, "name": "李佳薇"}],
               "al": {"id": 100, "name": "甲乙丙丁"}}
            ]
        """.trimIndent()
        val arr = JsonParser.parseString(json).asJsonArray
        val tracks = WyProvider.parseTracks(arr)
        assertEquals(2, tracks.size)
        val t = tracks.first()
        assertEquals("wy", t.platform)
        assertEquals("1974443814", t.remoteTrackId)
        assertEquals("海屿你", t.songName)
        assertEquals("马也_Crabbit", t.artistName)
        assertEquals("海屿你", t.albumName)
        assertEquals(295_000L, t.durationMs)
        assertTrue("应带封面", t.coverUrl!!.endsWith(".jpg"))
        assertTrue("应登记音质档位供取链降级", t.qualitys.map { it.type }.contains(QualityType.K320))
    }

    @Test
    fun `兼容老接口的 artists 与 album 字段`() {
        val json = """
            [
              {"id": 347230, "name": "海阔天空", "duration": 326000,
               "artists": [{"name": "Beyond"}],
               "album": {"id": 1, "name": "乐与怒"}}
            ]
        """.trimIndent()
        val tracks = WyProvider.parseTracks(JsonParser.parseString(json).asJsonArray)
        assertEquals(1, tracks.size)
        assertEquals("Beyond", tracks.first().artistName)
        assertEquals("乐与怒", tracks.first().albumName)
        assertEquals(326_000L, tracks.first().durationMs)
    }

    @Test
    fun `脏数据单条跳过不影响整批`() {
        val json = """
            [
              {"id": 1, "name": "正常歌", "ar": [{"name": "A"}]},
              {"name": "缺 id"},
              {"id": 3},
              {"id": 4, "name": "另一首", "ar": [{"name": "B"}]}
            ]
        """.trimIndent()
        val tracks = WyProvider.parseTracks(JsonParser.parseString(json).asJsonArray)
        assertEquals("缺 id / 缺歌名的条目应被跳过", 2, tracks.size)
        assertEquals("正常歌", tracks[0].songName)
        assertEquals("另一首", tracks[1].songName)
    }
}
