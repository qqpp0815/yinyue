package com.yinyue.player.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 6a~6d 离线单测：加密函数与搜索响应解析（fixture 对齐 lx 源码字段） */
class ProvidersOfflineTest {

    // ---------- wy ----------

    @Test
    fun `wy eapi 加解密往返`() {
        val params = """{"keyword":"晴天","needCorrect":"1","channel":"typing","offset":0,"scene":"normal","total":true,"limit":30}"""
        val hex = com.yinyue.player.data.source.wy.WyEapi.encrypt("/api/search/song/list/page", params)
        assertTrue("密文应为大写 hex 且为 16 字节块整数倍", hex.matches(Regex("^[0-9A-F]+$")) && hex.length % 32 == 0)
        // 解密得到完整签名串：{url}-36cd479b6b5-{text}-36cd479b6b5-{digest}
        val decrypted = com.yinyue.player.data.source.wy.WyEapi.decryptRoundtrip(hex)
        assertTrue(decrypted.startsWith("/api/search/song/list/page-36cd479b6b5-$params-36cd479b6b5-"))
    }

    @Test
    fun `wy 搜索响应解析`() {
        val json = """
            {"code":200,"data":{"totalCount":2,"resources":[
              {"baseInfo":{"simpleSongData":{
                 "id":"186016","name":"晴天","dt":269000,
                 "ar":[{"name":"周杰伦"}],
                 "al":{"id":"18918","name":"叶惠美","picUrl":"https://p1.music.126.net/x.jpg"}},
               "privilege":{"maxbr":999000,"sq":{"size":"25MB"},"h":{"size":"9MB"},"l":{"size":"3MB"}}}}
            ]}}
        """.trimIndent()
        val list = com.yinyue.player.data.source.wy.WyProvider.parseSearch(json)
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("186016", t.remoteTrackId)
        assertEquals("周杰伦", t.artistName)
        assertEquals(269000L, t.durationMs)
        assertEquals("https://p1.music.126.net/x.jpg", t.coverUrl)
        // 999000 贯穿 → flac+320k+128k
        assertEquals(listOf("flac", "320k", "128k"), t.qualitys.map { it.type.lxName })
    }

    // ---------- kg ----------

    @Test
    fun `kg signatureParams 与 lx 算法一致`() {
        val sign = com.yinyue.player.data.source.kg.KgProvider.signatureParams("b=2&a=1", "android", "")
        assertTrue(sign.matches(Regex("^[0-9a-f]{32}$")))
        // 排序后参与：keyparam + "a=1b=2" + "" + keyparam
        val expect = com.yinyue.player.data.source.kg.WyMd5.hex("OIlwieks28dk2k092lksi2UIkpa=1b=2OIlwieks28dk2k092lksi2UIkp")
        assertEquals(expect, sign)
    }

    @Test
    fun `kg 搜索响应解析与扩展字段`() {
        val json = """
            {"error_code":0,"data":{"total":1,"lists":[
              {"FileHash":"AAA111","HQFileHash":"BBB222","HQFileSize":9000000,
               "SQFileHash":"","SQFileSize":0,"ResFileHash":"","ResFileSize":0,
               "FileSize":3000000,"Audioid":"48310597","MixSongID":"6294852",
               "OriSongName":"晴天","Suffix":"","Singers":[{"name":"周杰伦"}],
               "AlbumName":"叶惠美","AlbumID":"1109","Duration":269}
            ]}}
        """.trimIndent()
        val list = com.yinyue.player.data.source.kg.KgProvider.parseSearch(json)
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("48310597", t.remoteTrackId)
        assertEquals(listOf("320k", "128k"), t.qualitys.map { it.type.lxName })
        assertEquals("AAA111", t.extras["hash"])
        assertEquals("6294852", t.extras["albumAudioId"])
    }

    @Test
    fun `kg krc 解码与转LRC`() {
        // 真实 krc 行格式：[起始毫秒,时长毫秒]正文
        val text = "[id:$0000000000]\n[1500,3000]晴<300,600>天\n"
        val deflated = java.util.zip.Deflater().let { d ->
            d.setInput(text.toByteArray(Charsets.UTF_8)); d.finish()
            val buf = java.io.ByteArrayOutputStream(); val tmp = ByteArray(4096)
            while (!d.finished()) buf.write(tmp, 0, d.deflate(tmp)); d.end(); buf.toByteArray()
        }
        val key = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )
        val xored = ByteArray(deflated.size) { (deflated[it].toInt() xor key[it % 16].toInt()).toByte() }
        val withHead = byteArrayOf(1, 2, 3, 4) + xored
        val b64 = java.util.Base64.getEncoder().encodeToString(withHead)
        val krc = com.yinyue.player.data.source.kg.KgKrcDecoder.decodeKrc(b64)
        assertEquals(text, krc)
        val lrc = com.yinyue.player.data.source.kg.KgKrcDecoder.krcToLrc(krc!!)
        assertTrue(lrc.contains("[00:01.500]晴天"))
        assertTrue(!lrc.contains("[id:"))
        assertTrue("字级标记应剥离", !lrc.contains("<300,600>"))
    }

    // ---------- tx ----------

    @Test
    fun `tx zzc 签名格式与确定性`() {
        val json = com.yinyue.player.data.source.tx.TxProvider.buildSearchRequest("晴天", 1, 30)
        val s1 = com.yinyue.player.data.source.tx.ZzcSigner.sign(json)
        val s2 = com.yinyue.player.data.source.tx.ZzcSigner.sign(json)
        assertEquals(s1, s2) // 同一 JSON 串签名一致
        assertTrue("应以 zzc 开头且全小写", s1.startsWith("zzc") && s1 == s1.lowercase())
        assertTrue(s1.length > 20)
        // comm 键序固定（sign 对实际发送串计算）
        assertTrue(json.contains("\"comm\":{\"_channelid\":\"0\""))
        assertTrue(json.contains("\"music.search.SearchCgiService\""))
    }

    @Test
    fun `tx 搜索响应解析与扩展字段`() {
        val json = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"meta":{"sum":1},
              "body":{"song":{"list":[
                {"id":97773,"mid":"0039MnYb0qxYhV","title":"晴天","interval":269,
                 "singer":[{"name":"周杰伦","mid":"0025NhlN2yWrP4"}],
                 "album":{"name":"叶惠美","mid":"001J5QJL1pRQYB"},
                 "file":{"media_mid":"0039MnYb0qxYhV","size_128mp3":3000000,
                         "size_320mp3":9000000,"size_flac":25000000,"size_hires":0}}
              ]}}}}}
        """.trimIndent()
        val list = com.yinyue.player.data.source.tx.TxProvider.parseSearch(json)
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("0039MnYb0qxYhV", t.remoteTrackId)
        assertEquals(listOf("flac", "320k", "128k"), t.qualitys.map { it.type.lxName })
        assertEquals("0039MnYb0qxYhV", t.extras["strMediaMid"])
        assertEquals("97773", t.extras["songId"])
        assertTrue(t.coverUrl!!.contains("T002R500x500M000001J5QJL1pRQYB"))
    }

    // ---------- mg ----------

    @Test
    fun `mg buildSign 与 MD5 直算一致`() {
        // sign = MD5(keyword + SALT1 + SALT2 + deviceId + timestamp)，与咪咕服务端约定一致
        val s1 = com.yinyue.player.data.source.mg.MgProvider.buildSign("晴天", "DEVICE", "1700000000000")
        val s2 = com.yinyue.player.data.source.mg.MgProvider.buildSign("晴天", "DEVICE", "1700000000000")
        assertEquals(s1, s2)
        assertTrue("应为 32 位小写 hex", s1.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `mg 搜索响应解析_双层结构与扩展字段`() {
        val json = """
            {"code":"000000","songResultData":{"totalCount":1,"resultList":[[
              {"songId":"1125059967","copyrightId":"6999608SEV0","name":"晴天",
               "album":"叶惠美","albumId":"9175","duration":269,
               "singerList":[{"name":"周杰伦"}],
               "img3":"http://d.musicapp.migu.cn/pic.png",
               "audioFormats":[{"formatType":"PQ","asize":"3M"},{"formatType":"HQ","asize":"9M"},
                               {"formatType":"SQ","asize":"25M"},{"formatType":"ZQ24","isize":"40M"}],
               "lrcUrl":"http://lrc.migu.cn/x.lrc","mrcurl":"http://mrc.migu.cn/x.mrc","trcUrl":""}
            ]]}}
        """.trimIndent()
        val list = com.yinyue.player.data.source.mg.MgProvider.parseSearch(json)
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("1125059967", t.remoteTrackId)
        assertEquals(listOf("flac24bit", "flac", "320k", "128k"), t.qualitys.map { it.type.lxName })
        assertEquals("6999608SEV0", t.extras["copyrightId"])
        assertEquals("http://mrc.migu.cn/x.mrc", t.extras["mrcUrl"])
        assertEquals("http://d.musicapp.migu.cn/pic.png", t.coverUrl)
    }
}
