package com.yinyue.player.data.source.kg

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yinyue.player.data.source.PlatformException
import com.yinyue.player.data.source.PlatformHttp
import com.yinyue.player.data.source.SourceProvider
import com.yinyue.player.data.source.model.QualityType
import com.yinyue.player.data.source.model.RawTrack
import com.yinyue.player.data.source.model.SearchPage
import com.yinyue.player.data.source.model.TopListBoard
import com.yinyue.player.data.source.model.TrackQuality
import java.util.zip.Inflater

/**
 * 酷狗（kg）元数据源，移植自 lx musicSdk/kg：
 * song_search_v2 搜索（无签名）、lyrics.kugou.com 两步歌词（krc 解码）、get_res_privilege 封面。
 */
class KgProvider : SourceProvider {

    override val platform = "kg"
    override val displayName = "酷狗"

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchPage =
        searchWithRetry(keyword, page, limit, retryNum = 0)

    private suspend fun searchWithRetry(keyword: String, page: Int, limit: Int, retryNum: Int): SearchPage {
        if (retryNum > 3) throw PlatformException("tryMaxNum", "kg search retry exhausted")
        val url = "http://songsearch.kugou.com/song_search_v2?platform=AndroidFilter&iscorrection=1" +
            "&keyword=${enc(keyword)}&hifiquality=0&pagesize=$limit&PrivilegeFilter=0&page=$page"
        val resp = PlatformHttp.get(url, headers = emptyMap()) // 默认 OkHttp UA（lx 走默认 Chrome69）
        if (resp.statusCode != 200) throw PlatformException("http${resp.statusCode}", "kg search http ${resp.statusCode}")
        val root = JsonParser.parseString(resp.body).asJsonObject
        if (root.get("error_code")?.asInt != 0) return searchWithRetry(keyword, page, limit, retryNum + 1)
        val tracks = parseSearch(resp.body!!)
        return SearchPage(tracks, page, root.getAsJsonObject("data")?.get("total")?.asInt ?: tracks.size, 1)
    }

    override suspend fun lyric(remoteTrackId: String): String? {
        // kg 歌词需要 keyword+hash+timelength，此处只有 id；由调用方优先使用 Track.lyricInfo
        // （v3.0 歌词主链路：搜索结果缓存 lrcContent），此接口仅作占位兜底。
        return null
    }

    override suspend fun cover(remoteTrackId: String): String? = null // 封面随搜索结果携带/按需走 privilege 接口

    // ── 排行榜（酷狗榜单：中文榜单为主，作为发现页的榜单来源）──

    override suspend fun topLists(): List<TopListBoard> {
        val body = PlatformHttp.get(
            "http://mobilecdnbj.kugou.com/api/v3/rank/list?version=9108&plat=0&showtype=2" +
                "&parentid=0&apiver=6&area_code=1&withsong=1",
            headers = emptyMap(),
        ).body ?: return emptyList()
        return runCatching {
            val info = JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("data")?.getAsJsonArray("info")
                ?: return@runCatching emptyList<TopListBoard>()
            val out = ArrayList<TopListBoard>()
            fun add(o: JsonObject) {
                val id = o.get("rankid")?.takeIf { !it.isJsonNull }?.asString ?: return
                val name = o.get("rankname")?.asString ?: return
                if (id == "0" || name.isBlank()) return
                out.add(TopListBoard(platform, id, name))
            }
            for (el in info) {
                val o = el.asJsonObject
                add(o)
                // 榜单分组（如「特色榜」下的子榜）一并展开
                o.getAsJsonArray("children")?.forEach { c ->
                    c.takeIf { it.isJsonObject }?.let { add(it.asJsonObject) }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    override suspend fun topListSongs(boardId: String): List<RawTrack> {
        val body = PlatformHttp.get(
            "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&plat=0&pagesize=100" +
                "&area_code=1&page=1&rankid=$boardId&with_res_tag=0&apiver=6",
            headers = emptyMap(),
        ).body ?: return emptyList()
        return runCatching {
            val info = JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("data")?.getAsJsonArray("info")
                ?: return@runCatching emptyList<RawTrack>()
            info.mapNotNull { el -> parseRankSong(el.asJsonObject) }
        }.getOrDefault(emptyList())
    }

    /** 榜单条目 → RawTrack（字段名与搜索接口不同：hash/320hash/sqhash + authors[].author_name） */
    private fun parseRankSong(o: JsonObject): RawTrack? {
        val audioId = o.get("audio_id")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val hash = o.get("hash")?.takeIf { !it.isJsonNull }?.asString ?: return null
        fun quality(hashField: String, sizeField: String, type: QualityType): TrackQuality? {
            val h = o.get(hashField)?.takeIf { !it.isJsonNull }?.asString
            val size = o.get(sizeField)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            if (h.isNullOrEmpty() || h == "0" || size <= 0L) return null
            return TrackQuality(type, "${size / 1024 / 1024}MB")
        }
        val qualitys = listOfNotNull(
            quality("hash_super", "filesize_super", QualityType.FLAC24BIT),
            quality("sqhash", "sqfilesize", QualityType.FLAC),
            quality("320hash", "320filesize", QualityType.K320),
            quality("hash", "filesize", QualityType.K128),
        )
        val singer = o.getAsJsonArray("authors")
            ?.joinToString("、") { a -> a.asJsonObject.get("author_name")?.asString ?: "" } ?: ""
        return RawTrack(
            platform = platform,
            remoteTrackId = audioId,
            songName = o.get("songname")?.asString ?: "",
            artistName = singer,
            albumName = o.get("remark")?.takeIf { !it.isJsonNull }?.asString,
            albumId = o.get("album_id")?.takeIf { !it.isJsonNull }?.asString,
            durationMs = o.get("duration")?.takeIf { !it.isJsonNull }?.asLong?.times(1000),
            qualitys = qualitys,
            extras = mapOf(
                "hash" to hash,
                "albumAudioId" to (o.get("album_audio_id")?.takeIf { !it.isJsonNull }?.asString ?: ""),
            ),
        )
    }

    companion object {
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /** signatureParams（lx kg/util.js）：md5(keyparam + 排序参数串 + body + keyparam) */
        fun signatureParams(params: String, platform: String = "android", body: String = ""): String {
            val keyparam = if (platform == "web") "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt" else "OIlwieks28dk2k092lksi2UIkp"
            val paramList = params.split("&").sorted()
            val signParams = "$keyparam${paramList.joinToString("")}$body$keyparam"
            return WyMd5.hex(signParams)
        }

        fun parseSearch(body: String): List<RawTrack> {
            val out = ArrayList<RawTrack>()
            runCatching {
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonObject("data") ?: return out
                val lists = data.getAsJsonArray("lists") ?: return out
                val seen = HashSet<String>()
                for (item in lists) {
                    val o = item.asJsonObject
                    val hash = o.get("FileHash")?.asString ?: continue
                    val audioId = o.get("Audioid")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val dedupKey = "$audioId$hash"
                    if (!seen.add(dedupKey)) continue
                    fun hashOf(hashField: String, sizeField: String): TrackQuality? {
                        val h = o.get(hashField)?.takeIf { !it.isJsonNull }?.asString
                        if (h.isNullOrEmpty() || h == "0" || (o.get(sizeField)?.asLong ?: 0L) == 0L) return null
                        val type = when (hashField) {
                            "FileHash" -> QualityType.K128
                            "HQFileHash" -> QualityType.K320
                            "SQFileHash" -> QualityType.FLAC
                            "ResFileHash" -> QualityType.FLAC24BIT
                            else -> return null
                        }
                        return TrackQuality(type, o.get(sizeField)?.takeIf { !it.isJsonNull }?.asString)
                    }
                    val qualitys = listOfNotNull(
                        hashOf("ResFileHash", "ResFileSize"),
                        hashOf("SQFileHash", "SQFileSize"),
                        hashOf("HQFileHash", "HQFileSize"),
                        hashOf("FileHash", "FileSize"),
                    )
                    val suffix = o.get("Suffix")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val singer = o.getAsJsonArray("Singers")
                        ?.joinToString("、") { s -> s.asJsonObject.get("name")?.asString ?: "" } ?: ""
                    out.add(
                        RawTrack(
                            platform = "kg",
                            remoteTrackId = audioId,
                            songName = (o.get("OriSongName")?.asString ?: "") + (if (suffix.isNotBlank()) " $suffix" else ""),
                            artistName = singer,
                            albumName = o.get("AlbumName")?.takeIf { !it.isJsonNull }?.asString,
                            albumId = o.get("AlbumID")?.takeIf { !it.isJsonNull }?.asString,
                            durationMs = o.get("Duration")?.takeIf { !it.isJsonNull }?.asLong?.times(1000),
                            qualitys = qualitys,
                            extras = mapOf("hash" to hash, "albumAudioId" to o.get("MixSongID")?.takeIf { !it.isJsonNull }?.asString),
                        ),
                    )
                }
            }
            return out
        }
    }
}

/** kg krc 解码（lx common/utils/lyricUtils/kg.js）：base64 → 丢前4字节 → XOR 16字节key → inflate */
object KgKrcDecoder {
    private val ENC_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    fun decodeKrc(contentBase64: String): String? = runCatching {
        val raw = java.util.Base64.getDecoder().decode(contentBase64)
        if (raw.size <= 4) return@runCatching null
        val xored = ByteArray(raw.size - 4)
        for (i in xored.indices) {
            xored[i] = (raw[i + 4].toInt() xor ENC_KEY[i % 16].toInt()).toByte()
        }
        inflate(xored)?.toString(Charsets.UTF_8)
    }.getOrNull()

    /** krc 文本 → 标准 LRC（丢弃 [id:]/[language:] 段，剥字级标记，行时间 [ms,ms] → [mm:ss.SSS]） */
    fun krcToLrc(krcText: String): String {
        val sb = StringBuilder()
        for (line in krcText.lines()) {
            val t = line.trim()
            when {
                t.isEmpty() -> continue
                t.startsWith("[id:") || t.startsWith("[language:") || t.startsWith("[offset:") -> continue
                t.startsWith("[ti:") || t.startsWith("[ar:") || t.startsWith("[al:") -> sb.append(t).append('\n')
                else -> {
                    val m = Regex("""^\[(\d+),(\d+)](.*)$""").find(t) ?: continue
                    val startMs = m.groupValues[1].toLong()
                    val body = m.groupValues[3].replace(Regex("""<\d+,\d+,\d+>"""), "")
                        .replace(Regex("""<\d+,\d+>"""), "")
                        .trim()
                    sb.append('[').append(fmt(startMs)).append(']').append(body).append('\n')
                }
            }
        }
        return sb.toString()
    }

    fun fmt(ms: Long): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        val milli = ms % 1000
        return String.format("%02d:%02d.%03d", min, sec, milli)
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(tmp)
            if (n == 0 && inflater.needsInput()) break
            buffer.write(tmp, 0, n)
        }
        inflater.end()
        buffer.toByteArray()
    } catch (e: Exception) {
        null
    }
}

object WyMd5 {
    fun hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
