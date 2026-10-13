package com.yinyue.player.data.source.tx

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yinyue.player.data.source.PlatformException
import com.yinyue.player.data.source.PlatformHttp
import com.yinyue.player.data.source.SourceProvider
import com.yinyue.player.data.source.model.QualityType
import com.yinyue.player.data.source.model.RawTrack
import com.yinyue.player.data.source.model.SearchPage
import com.yinyue.player.data.source.model.TrackQuality
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/** QQ音乐 zzc 签名（移植自 lx tx/utils/crypto.js）：sha1 → 取位 → SCRAMBLE 异或 → base64 去号 */
object ZzcSigner {
    private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
    private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val SCRAMBLE_VALUES = intArrayOf(
        89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179,
    )

    fun sign(jsonText: String): String {
        val hash = sha1Hex(jsonText)
        // 对齐 JS 语义：下标越界（如 40 超出 40 位 hex）取空串
        val part1 = PART_1_INDEXES.map { hash.getOrNull(it) }.filterNotNull().joinToString("")
        val part2 = PART_2_INDEXES.map { hash.getOrNull(it) }.filterNotNull().joinToString("")
        val part3 = ByteArray(20)
        for (i in 0 until 20) {
            val hexByte = hash.substring(i * 2, i * 2 + 2).toInt(16)
            part3[i] = (SCRAMBLE_VALUES[i] xor hexByte).toByte()
        }
        val b64 = Base64.getEncoder().encodeToString(part3)
            .replace("+", "").replace("/", "").replace("=", "")
        return ("zzc" + part1 + b64 + part2).lowercase()
    }

    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * QQ音乐（tx）元数据源，移植自 lx musicSdk/tx：
 * musics.fcg DoSearchForQQMusicDesktop（JSON body + zzc 签名）、封面按专辑/歌手 mid 拼 URL。
 * 歌词（QRC 非标准 3DES 变体）暂缓，返回 null（歌词主链路由 wy/kg/kw 提供）。
 */
class TxProvider : SourceProvider {

    override val platform = "tx"
    override val displayName = "QQ音乐"

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchPage =
        searchWithRetry(keyword, page, limit, retryNum = 0)

    private suspend fun searchWithRetry(keyword: String, page: Int, limit: Int, retryNum: Int): SearchPage {
        if (retryNum > 5) throw PlatformException("tryMaxNum", "tx search retry exhausted")
        val json = buildSearchRequest(keyword, page, limit)
        val sign = ZzcSigner.sign(json)
        val resp = PlatformHttp.post(
            "https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sign",
            json,
            contentType = "application/json",
            headers = mapOf("User-Agent" to "QQMusic 14090508(android 12)"),
        )
        if (resp.statusCode != 200) throw PlatformException("http${resp.statusCode}", "tx search http ${resp.statusCode}")
        val tracks = parseSearch(resp.body!!)
        if (tracks.isEmpty()) return searchWithRetry(keyword, page, limit, retryNum + 1)
        return SearchPage(tracks, page, tracks.size, 1)
    }

    override suspend fun lyric(remoteTrackId: String): String? = null // QRC 非标准 DES 变体，暂缓

    override suspend fun cover(remoteTrackId: String): String? = null // 封面随搜索结果携带

    companion object {
        private const val GUID = "1F70E520B2EAA7D25E11760783C53CA9"

        /** JSON 键序与字段类型必须与 lx 保持一致（sign 对实际发送串计算） */
        fun buildSearchRequest(keyword: String, page: Int, limit: Int): String {
            val comm = JsonObject().apply {
                addProperty("_channelid", "0")
                addProperty("_os_version", "6.2.9200-2")
                addProperty("ct", 19)
                addProperty("cv", 2151)
                addProperty("guid", GUID)
                addProperty("patch", 118)
                addProperty("psrf_access_token_expiresAt", 0)
                addProperty("psrf_qqaccess_token", "")
                addProperty("psrf_qqopenid", "")
                addProperty("psrf_qqunionid", "")
                addProperty("tmeAppID", "qqmusic")
                addProperty("tmeLoginType", 0)
                addProperty("uin", "0")
                addProperty("wid", "7223299733393904640")
            }
            val param = JsonObject().apply {
                addProperty("grp", 1)
                addProperty("num_per_page", limit)
                addProperty("page_num", page)
                addProperty("query", keyword)
                addProperty("remoteplace", "txt.newclient.top")
                addProperty("search_type", 0)
                addProperty("searchid", genSearchId())
            }
            val req = JsonObject().apply {
                addProperty("module", "music.search.SearchCgiService")
                addProperty("method", "DoSearchForQQMusicDesktop")
                add("param", param)
            }
            val root = JsonObject().apply {
                add("comm", comm)
                add("music.search.SearchCgiService", req)
            }
            return root.toString()
        }

        private fun genSearchId(): String {
            val hex32 = (1..32).map { "0123456789ABCDEF"[Random.nextInt(16)] }.joinToString("")
            val five = "%05d".format(Random.nextInt(100000))
            return hex32 + five
        }

        fun parseSearch(body: String): List<RawTrack> {
            val out = ArrayList<RawTrack>()
            runCatching {
                val root = JsonParser.parseString(body).asJsonObject
                if (root.get("code")?.asInt != 0) return out
                val req = root.getAsJsonObject("music.search.SearchCgiService") ?: root.getAsJsonObject("req") ?: return out
                if (req.get("code")?.asInt != 0) return out
                val data = req.getAsJsonObject("data") ?: return out
                val list = data.getAsJsonObject("body")?.getAsJsonObject("song")?.getAsJsonArray("list") ?: return out
                for (item in list) {
                    val o = item.asJsonObject
                    val file = o.getAsJsonObject("file") ?: continue
                    val mediaMid = file.get("media_mid")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val mid = o.get("mid")?.asString ?: continue
                    fun sizeOf(field: String): String? {
                        val v = file.get(field)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        return if (v == 0L) null else v.toString()
                    }
                    val qualitys = listOfNotNull(
                        sizeOf("size_hires")?.let { TrackQuality(QualityType.FLAC24BIT, it) },
                        sizeOf("size_flac")?.let { TrackQuality(QualityType.FLAC, it) },
                        sizeOf("size_320mp3")?.let { TrackQuality(QualityType.K320, it) },
                        sizeOf("size_128mp3")?.let { TrackQuality(QualityType.K128, it) },
                    )
                    val singer = o.getAsJsonArray("singer")
                        ?.joinToString("、") { s -> s.asJsonObject.get("name")?.asString ?: "" } ?: ""
                    val album = o.getAsJsonObject("album")
                    val albumMid = album?.get("mid")?.takeIf { !it.isJsonNull }?.asString
                    val coverUrl = albumMid?.takeIf { it.isNotBlank() && it != "空" }
                        ?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" }
                        ?: o.getAsJsonArray("singer")?.firstOrNull()?.asJsonObject?.get("mid")?.asString
                            ?.let { "https://y.gtimg.cn/music/photo_new/T001R500x500M000$it.jpg" }
                    out.add(
                        RawTrack(
                            platform = "tx",
                            remoteTrackId = mid,
                            songName = o.get("title")?.asString ?: "",
                            artistName = singer,
                            albumName = album?.get("name")?.takeIf { !it.isJsonNull }?.asString,
                            albumId = albumMid,
                            durationMs = o.get("interval")?.takeIf { !it.isJsonNull }?.asLong?.times(1000),
                            coverUrl = coverUrl,
                            qualitys = qualitys,
                            extras = mapOf(
                                "strMediaMid" to mediaMid,
                                "albumMid" to albumMid,
                                "songId" to o.get("id")?.takeIf { !it.isJsonNull }?.asString,
                            ),
                        ),
                    )
                }
            }
            return out
        }
    }
}
