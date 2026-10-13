package com.yinyue.player.data.source.kw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KwParseTest {

    @Test
    fun `搜索结果解析与音质映射`() {
        val json = """
            {"TOTAL":"2","SHOW":"2","abslist":[
              {"MUSICRID":"MUSIC_22848",
               "N_MINFO":"level:t1,bitrate:128,format:mp3,size:3.65M;level:t4,bitrate:2000,format:flac,size:25.19M",
               "SONGNAME":"晴天","ARTIST":"周杰伦","DURATION":"269","ALBUMID":"5325","ALBUM":"叶惠美"}
            ]}
        """.trimIndent()
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val list = KwProvider::class.java.getDeclaredMethod("parseAbsList", com.google.gson.JsonElement::class.java)
            .apply { isAccessible = true }
            .invoke(KwProvider(), root.get("abslist")) as List<com.yinyue.player.data.source.model.RawTrack>
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("22848", t.remoteTrackId)
        assertEquals("晴天", t.songName)
        assertEquals("周杰伦", t.artistName)
        assertEquals(269000L, t.durationMs)
        // lx 行为：types 反转，高音质在前
        assertEquals(listOf("flac", "128k"), t.qualitys.map { it.type.lxName })
        assertEquals("25.19M", t.qualitys.first().sizeLabel)
    }

    @Test
    fun `旧版musicInfo JSON 与 lx toOldMusicInfo 字段对齐`() {
        val track = com.yinyue.player.data.source.model.RawTrack(
            platform = "kw", remoteTrackId = "22848", songName = "晴天", artistName = "周杰伦",
            albumName = "叶惠美", albumId = "5325", durationMs = 269000L,
            qualitys = listOf(
                com.yinyue.player.data.source.model.TrackQuality(com.yinyue.player.data.source.model.QualityType.FLAC, "25.19M"),
                com.yinyue.player.data.source.model.TrackQuality(com.yinyue.player.data.source.model.QualityType.K128, "3.65M"),
            ),
        )
        val json = track.toOldMusicInfoJson().toString()
        assertTrue(json.contains("\"source\":\"kw\""))
        assertTrue(json.contains("\"songmid\":\"22848\""))
        assertTrue(json.contains("\"interval\":\"04:29\""))
        assertTrue(json.contains("\"flac24bit\"") || json.contains("\"flac\""))
        assertTrue(json.contains("\"types\":[{\"type\":\"flac\""))
        assertTrue(json.contains("\"_types\":{\"flac\":{\"size\":\"25.19M\"}"))
    }

    @Test
    fun `decodeName 实体还原`() {
        assertEquals("A&B", KwProvider.decodeName("A&amp;B"))
        assertEquals("中", KwProvider.decodeName("&#20013;"))
    }
}
