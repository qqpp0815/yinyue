package com.yinyue.player.data.source.wy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yinyue.player.data.source.PlatformException
import com.yinyue.player.data.source.PlatformHttp
import com.yinyue.player.data.source.SourceProvider
import com.yinyue.player.data.source.model.PlaylistBrief
import com.yinyue.player.data.source.model.QualityType
import com.yinyue.player.data.source.model.RawTrack
import com.yinyue.player.data.source.model.SearchPage
import com.yinyue.player.data.source.model.TopListBoard
import com.yinyue.player.data.source.model.TrackQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 网易云 eapi 加密（移植自 lx wy/utils/crypto.js）：AES-128-ECB，key e82ckenh8dichen8 */
object WyEapi {
    private const val KEY = "e82ckenh8dichen8"

    fun encrypt(urlPath: String, text: String): String {
        val digest = md5Hex("nobody${urlPath}use${text}md5forencrypt")
        val data = "${urlPath}-36cd479b6b5-$text-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }

    /** 供离线单测做加密/解密往返校验 */
    fun decryptRoundtrip(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 网易云（wy）元数据源，移植自 lx musicSdk/wy：
 * eapi/batch 搜索（/api/search/song/list/page）、eapi 歌词 v1、封面取搜索结果 al.picUrl。
 */
class WyProvider : SourceProvider {

    override val platform = "wy"
    override val displayName = "网易云"

    private val searchPath = "/api/search/song/list/page"
    private val lyricPath = "/api/song/lyric/v1"

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        "origin" to "https://music.163.com",
    )

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchPage =
        searchWithRetry(keyword, page, limit, retryNum = 0)

    private suspend fun searchWithRetry(keyword: String, page: Int, limit: Int, retryNum: Int): SearchPage {
        if (retryNum > 3) throw PlatformException("tryMaxNum", "wy search retry exhausted")
        val params = JsonObject().apply {
            addProperty("keyword", keyword)
            addProperty("needCorrect", "1")
            addProperty("channel", "typing")
            addProperty("offset", limit * (page - 1))
            addProperty("scene", "normal")
            addProperty("total", page == 1) // 布尔字面量，键序不可变
            addProperty("limit", limit)
        }
        val text = params.toString()
        val form = "params=${WyEapi.encrypt(searchPath, text)}"
        val resp = PlatformHttp.post(
            "http://interface.music.163.com/eapi/batch",
            form,
            headers = searchHeaders,
        )
        if (resp.statusCode != 200) throw PlatformException("http${resp.statusCode}", "wy search http ${resp.statusCode}")
        val list = parseSearch(resp.body!!)
        if (list.isEmpty()) return searchWithRetry(keyword, page, limit, retryNum + 1)
        return SearchPage(list, page, list.size, 1)
    }

    override suspend fun lyric(remoteTrackId: String): String? {
        val params = JsonObject().apply {
            addProperty("id", remoteTrackId)
            addProperty("cp", false)
            addProperty("tv", 0)
            addProperty("lv", 0)
            addProperty("rv", 0)
            addProperty("kv", 0)
            addProperty("yv", 0)
            addProperty("ytv", 0)
            addProperty("yrv", 0)
        }
        val form = "params=${WyEapi.encrypt(lyricPath, params.toString())}"
        val resp = PlatformHttp.post(
            "https://interface3.music.163.com/eapi/song/lyric/v1",
            form,
            headers = searchHeaders,
        )
        if (resp.statusCode != 200) return null
        return runCatching {
            val root = JsonParser.parseString(resp.body).asJsonObject
            val lrc = root.getAsJsonObject("lrc")?.get("lyric")?.asString ?: return@runCatching null
            // fixTimeLabel：[(mm:ss):(ss)] → [mm:ss.ss]
            Regex("""\[(\d{2}:\d{2}):(\d{2})]""").replace(lrc, "[$1.$2]")
        }.getOrNull()
    }

    override suspend fun cover(remoteTrackId: String): String? = null // 封面随搜索结果携带（al.picUrl）

    // ── 排行榜 / 推荐歌单（明文 web 接口，无需登录；榜单本质是歌单）──

    override suspend fun topLists(): List<TopListBoard> = TOP_BOARDS

    /** 分类候选：中文曲风在前（默认「全部」欧美占比高，用户普遍要的是华语/古风这类） */
    private val WY_TAGS = listOf(
        "华语", "流行", "古风", "民谣", "摇滚", "电子", "说唱", "轻音乐",
        "ACG", "影视原声", "爵士", "古典", "怀旧", "治愈", "伤感", "夜晚",
    )

    override suspend fun topListSongs(boardId: String): List<RawTrack> = fetchPlaylistTracks(boardId)

    /** 歌单分类（分类名直接作为 cat 传给接口） */
    override suspend fun playlistTags(): List<String> = WY_TAGS

    override suspend fun recommendPlaylists(order: String, offset: Int, limit: Int, cat: String): List<PlaylistBrief> =
        withContext(Dispatchers.IO) {
            val cat = java.net.URLEncoder.encode(cat.ifBlank { "全部" }, "UTF-8")
            val body = http.getString(
                "https://music.163.com/api/playlist/list?cat=$cat&order=$order&limit=$limit&offset=$offset&total=true",
            )
            val arr = JsonParser.parseString(body).asJsonObject.getAsJsonArray("playlists") ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                PlaylistBrief(
                    platform = platform,
                    playlistId = id,
                    name = o.get("name")?.asString ?: "",
                    coverUrl = o.get("coverImgUrl")?.takeIf { !it.isJsonNull }?.asString,
                    playCount = o.get("playCount")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    trackCount = o.get("trackCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                )
            }
        }

    override suspend fun playlistSongs(playlistId: String): List<RawTrack> = fetchPlaylistTracks(playlistId)

    /** 歌单/榜单详情：两种明文端点形态都试一遍（/api/playlist/detail 与 /api/v6/playlist/detail） */
    private suspend fun fetchPlaylistTracks(id: String): List<RawTrack> = withContext(Dispatchers.IO) {
        for (url in listOf(
            "https://music.163.com/api/playlist/detail?id=$id",
            "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000",
        )) {
            val tracks = runCatching {
                val root = JsonParser.parseString(http.getString(url)).asJsonObject
                val result = root.getAsJsonObject("result") ?: root.getAsJsonObject("playlist")
                    ?: return@runCatching emptyList<RawTrack>()
                val arr = result.getAsJsonArray("tracks") ?: result.getAsJsonArray("songs") ?: return@runCatching emptyList()
                parseTrackArray(arr)
            }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        emptyList()
    }

    private fun parseTrackArray(arr: com.google.gson.JsonArray): List<RawTrack> = parseTracks(arr)

    /** 明文 web 接口（与 eapi 搜索不同，这几个接口不需要加密） */
    private val http = PlainHttp()

    private class PlainHttp {
        suspend fun getString(url: String): String =
            com.yinyue.player.data.source.PlatformHttp.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Referer" to "https://music.163.com/",
                ),
            ).body ?: ""
    }

    companion object {
        /**
         * 歌单/榜单接口的 track 数组 → RawTrack。
         * 字段名与搜索接口不同（ar/al/dt vs baseInfo/simpleSongData），且老接口用 artists/album/duration，
         * 两种形态都兼容；解析失败的单条跳过，不影响整批。
         */
        fun parseTracks(arr: com.google.gson.JsonArray): List<RawTrack> = arr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                if (o.get("name")?.takeIf { !it.isJsonNull }?.asString.isNullOrBlank()) return@mapNotNull null
                val arArr = o.getAsJsonArray("ar") ?: o.getAsJsonArray("artists")
                val singer = arArr?.joinToString("、") { a ->
                    val ao = runCatching { a.asJsonObject }.getOrNull()
                    ao?.get("name")?.asString ?: a.asString
                } ?: ""
                val al = o.getAsJsonObject("al") ?: o.getAsJsonObject("album")
                RawTrack(
                    platform = "wy",
                    remoteTrackId = id,
                    songName = o.get("name").asString,
                    artistName = singer,
                    albumName = al?.get("name")?.takeIf { !it.isJsonNull }?.asString,
                    albumId = al?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                    durationMs = o.get("dt")?.takeIf { !it.isJsonNull }?.asLong
                        ?: o.get("duration")?.takeIf { !it.isJsonNull }?.asLong,
                    coverUrl = al?.get("picUrl")?.takeIf { !it.isJsonNull }?.asString,
                    // 榜单接口不带 privilege，按常见档位登记（取链时脚本会按声明降级）
                    qualitys = listOf(
                        TrackQuality(QualityType.K128),
                        TrackQuality(QualityType.K320),
                        TrackQuality(QualityType.FLAC),
                    ),
                    extras = emptyMap(),
                )
            }.getOrNull()
        }

        /** 内置榜单（bangid 取自落雪 wy/leaderboard.js，挑常用的若干个） */
        val TOP_BOARDS = listOf(
            TopListBoard("wy", "19723756", "飙升榜"),
            TopListBoard("wy", "3779629", "新歌榜"),
            TopListBoard("wy", "3778678", "热歌榜"),
            TopListBoard("wy", "2884035", "原创榜"),
            TopListBoard("wy", "1978921795", "电音榜"),
            TopListBoard("wy", "71385702", "ACG榜"),
            TopListBoard("wy", "5059642708", "国风榜"),
            TopListBoard("wy", "5059633707", "摇滚榜"),
            TopListBoard("wy", "5059661515", "民谣榜"),
            TopListBoard("wy", "991319590", "说唱榜"),
            TopListBoard("wy", "2809513713", "欧美热歌榜"),
            TopListBoard("wy", "5059644681", "日语榜"),
            TopListBoard("wy", "745956260", "韩语榜"),
            TopListBoard("wy", "71384707", "古典榜"),
            TopListBoard("wy", "60131", "日本Oricon榜"),
            TopListBoard("wy", "60198", "美国Billboard榜"),
        )

        /** 解析 wy 搜索响应（容错字段层级），空列表返回空集合 */
        fun parseSearch(body: String): List<RawTrack> {
            val out = ArrayList<RawTrack>()
            runCatching {
                val data = JsonParser.parseString(body).asJsonObject.getAsJsonObject("data")
                    ?: return out
                val resources = data.getAsJsonArray("resources") ?: return out
                for (r in resources) {
                    val res = r.asJsonObject
                    val baseInfo = res.getAsJsonObject("baseInfo") ?: res
                    val song = baseInfo.getAsJsonObject("simpleSongData") ?: continue
                    val id = song.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val arArr = song.getAsJsonArray("ar")
                    val singer = arArr?.joinToString("、") { a -> a.asJsonObject.get("name")?.asString ?: "" } ?: ""
                    val al = song.getAsJsonObject("al")
                    val durationMs = song.get("dt")?.takeIf { !it.isJsonNull }?.asLong
                    val privilege = res.getAsJsonObject("privilege")
                        ?: baseInfo.getAsJsonObject("privilege")
                        ?: song.getAsJsonObject("privilege")
                    val qualitys = parseQualitys(privilege)
                    out.add(
                        RawTrack(
                            platform = "wy",
                            remoteTrackId = id,
                            songName = song.get("name")?.asString ?: "",
                            artistName = singer,
                            albumName = al?.get("name")?.takeIf { !it.isJsonNull }?.asString,
                            albumId = al?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                            durationMs = durationMs,
                            coverUrl = al?.get("picUrl")?.takeIf { !it.isJsonNull }?.asString,
                            qualitys = qualitys,
                            extras = emptyMap(),
                        ),
                    )
                }
            }
            return out
        }

        /** maxBrLevel=hires→flac24bit；maxbr 999000→flac+320k+128k（lx 故意贯穿）；320000→320k+128k；其余→128k */
        private fun parseQualitys(privilege: JsonObject?): List<TrackQuality> {
            if (privilege == null) return emptyList()
            val maxbr = privilege.get("maxbr")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val maxBrLevel = privilege.get("maxBrLevel")?.takeIf { !it.isJsonNull }?.asString
            val q = ArrayList<TrackQuality>()
            fun sizeOf(field: String): String? =
                privilege.getAsJsonObject(field)?.get("size")?.takeIf { !it.isJsonNull }?.asString
            if (maxBrLevel == "hires") {
                q.add(TrackQuality(QualityType.FLAC24BIT, sizeOf("hr")))
            }
            when {
                maxbr >= 999000 -> {
                    q.add(TrackQuality(QualityType.FLAC, sizeOf("sq")))
                    q.add(TrackQuality(QualityType.K320, sizeOf("h")))
                    q.add(TrackQuality(QualityType.K128, sizeOf("l")))
                }
                maxbr >= 320000 -> {
                    q.add(TrackQuality(QualityType.K320, sizeOf("h")))
                    q.add(TrackQuality(QualityType.K128, sizeOf("l")))
                }
                else -> q.add(TrackQuality(QualityType.K128, sizeOf("l")))
            }
            return q
        }
    }
}
