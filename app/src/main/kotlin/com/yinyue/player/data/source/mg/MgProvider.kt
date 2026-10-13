package com.yinyue.player.data.source.mg

import com.google.gson.JsonParser
import com.yinyue.player.data.source.PlatformException
import com.yinyue.player.data.source.PlatformHttp
import com.yinyue.player.data.source.SourceProvider
import com.yinyue.player.data.source.model.QualityType
import com.yinyue.player.data.source.model.RawTrack
import com.yinyue.player.data.source.model.SearchPage
import com.yinyue.player.data.source.model.TrackQuality
import com.yinyue.player.data.source.kg.WyMd5

/**
 * 咪咕（mg）元数据源，移植自 lx musicSdk/mg：
 * jadeite v3 搜索（MD5 签名头）、封面/歌词 URL 随搜索结果携带；
 * mrc 逐字歌词（XXTEA 变体）暂缓，歌词取 lrcUrl 纯文本。
 */
class MgProvider : SourceProvider {

    override val platform = "mg"
    override val displayName = "咪咕"

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchPage =
        searchWithRetry(keyword, page, limit, retryNum = 0)

    private suspend fun searchWithRetry(keyword: String, page: Int, limit: Int, retryNum: Int): SearchPage {
        if (retryNum > 3) throw PlatformException("tryMaxNum", "mg search retry exhausted")
        val deviceId = DEVICE_ID
        val time = System.currentTimeMillis().toString()
        val sign = buildSign(keyword, deviceId, time)
        val switch = "%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D"
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=0&isCopyright=1" +
            "&searchSwitch=$switch&pageSize=$limit&text=${enc(keyword)}&pageNo=$page&sort=0&sid=USS"
        val headers = mapOf(
            "uiVersion" to "A_music_3.6.1",
            "deviceId" to deviceId,
            "timestamp" to time,
            "sign" to sign,
            "channel" to "0146921",
            "User-Agent" to "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30",
        )
        val resp = PlatformHttp.get(url, headers)
        if (resp.statusCode != 200) throw PlatformException("http${resp.statusCode}", "mg search http ${resp.statusCode}")
        val tracks = parseSearch(resp.body!!)
        if (tracks.isEmpty()) return searchWithRetry(keyword, page, limit, retryNum + 1)
        return SearchPage(tracks, page, tracks.size, 1)
    }

    override suspend fun lyric(remoteTrackId: String): String? {
        // remoteTrackId 即 songId；lrcUrl 需搜索结果携带，此处通过 resourceinfo 接口兜底
        return try {
            val resp = PlatformHttp.post(
                "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2",
                "resourceId=$remoteTrackId",
                headers = mapOf(
                    "Referer" to "https://app.c.nf.migu.cn/",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36",
                    "channel" to "0146921",
                ),
            )
            if (resp.statusCode != 200) return null
            val root = JsonParser.parseString(resp.body).asJsonObject
            if (root.get("code")?.asString != "000000") return null
            val resources = root.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
                ?.getAsJsonArray("resource") ?: return null
            var lrcUrl: String? = null
            for (r in resources) {
                lrcUrl = r.asJsonObject.get("lrcUrl")?.takeIf { !it.isJsonNull }?.asString ?: continue
                if (lrcUrl.isNotBlank()) break
            }
            if (lrcUrl.isNullOrBlank()) return null
            val lrcResp = PlatformHttp.get(
                lrcUrl,
                headers = mapOf(
                    "Referer" to "https://app.c.nf.migu.cn/",
                    "channel" to "0146921",
                ),
            )
            if (lrcResp.statusCode != 200) null else lrcResp.body
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun cover(remoteTrackId: String): String? = null // 封面随搜索结果携带

    companion object {
        private const val DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
        private const val SALT1 = "6cdc72a439cef99a3418d2a78aa28c73"
        private const val SALT2 = "yyapp2d16148780a1dcc7408e06336b98cfd50"

        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /** 搜索签名：MD5(keyword + SALT1 + SALT2 + deviceId + timestamp) */
        fun buildSign(keyword: String, deviceId: String, time: String): String =
            WyMd5.hex(keyword + SALT1 + SALT2 + deviceId + time)

        fun parseSearch(body: String): List<RawTrack> {
            val out = ArrayList<RawTrack>()
            runCatching {
                val root = JsonParser.parseString(body).asJsonObject
                if (root.get("code")?.asString != "000000") return out
                val songData = root.getAsJsonObject("songResultData") ?: return out
                val resultList = songData.getAsJsonArray("resultList") ?: return out
                val seen = HashSet<String>()
                // resultList 是数组的数组（双重遍历）
                for (group in resultList) {
                    if (!group.isJsonArray) continue
                    for (item in group.asJsonArray) {
                        val o = item.asJsonObject
                        val songId = o.get("songId")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val copyrightId = o.get("copyrightId")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        if (!seen.add(copyrightId)) continue
                        val formats = o.getAsJsonArray("audioFormats")
                        val qualitys = ArrayList<TrackQuality>()
                        if (formats != null) {
                            for (f in formats) {
                                val fo = f.asJsonObject
                                val formatType = fo.get("formatType")?.asString ?: continue
                                val size = fo.get("asize")?.takeIf { !it.isJsonNull }?.asString
                                    ?: fo.get("isize")?.takeIf { !it.isJsonNull }?.asString
                                val type = when (formatType) {
                                    "PQ" -> QualityType.K128
                                    "HQ" -> QualityType.K320
                                    "SQ" -> QualityType.FLAC
                                    "ZQ24" -> QualityType.FLAC24BIT
                                    else -> continue
                                }
                                qualitys.add(TrackQuality(type, size))
                            }
                            // 统一高音质在前（flac24bit 与 flac 同级时 24bit 优先）
                            qualitys.sortByDescending { it.type.level * 10 + if (it.type == QualityType.FLAC24BIT) 1 else 0 }
                        }
                        var coverUrl = o.get("img3")?.takeIf { !it.isJsonNull }?.asString
                            ?: o.get("img2")?.takeIf { !it.isJsonNull }?.asString
                            ?: o.get("img1")?.takeIf { !it.isJsonNull }?.asString
                        if (coverUrl != null && !coverUrl.startsWith("http")) {
                            coverUrl = "http://d.musicapp.migu.cn$coverUrl"
                        }
                        val singer = o.getAsJsonArray("singerList")
                            ?.joinToString("、") { s -> s.asJsonObject.get("name")?.asString ?: "" } ?: ""
                        out.add(
                            RawTrack(
                                platform = "mg",
                                remoteTrackId = songId,
                                songName = o.get("name")?.asString ?: "",
                                artistName = singer,
                                albumName = o.get("album")?.takeIf { !it.isJsonNull }?.asString,
                                albumId = o.get("albumId")?.takeIf { !it.isJsonNull }?.asString,
                                durationMs = o.get("duration")?.takeIf { !it.isJsonNull }?.asLong?.times(1000),
                                coverUrl = coverUrl,
                                qualitys = qualitys,
                                extras = mapOf(
                                    "copyrightId" to copyrightId,
                                    "lrcUrl" to o.get("lrcUrl")?.takeIf { !it.isJsonNull }?.asString,
                                    "mrcUrl" to o.get("mrcurl")?.takeIf { !it.isJsonNull }?.asString, // 响应字段全小写 u
                                    "trcUrl" to o.get("trcUrl")?.takeIf { !it.isJsonNull }?.asString,
                                ),
                            ),
                        )
                    }
                }
            }
            return out
        }
    }
}
