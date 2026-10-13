package com.yinyue.player.data.source.kw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yinyue.player.data.source.PlatformHttp
import com.yinyue.player.data.source.PlatformException
import com.yinyue.player.data.source.SourceProvider
import com.yinyue.player.data.source.model.PlaylistBrief
import com.yinyue.player.data.source.model.QualityType
import com.yinyue.player.data.source.model.RawTrack
import com.yinyue.player.data.source.model.SearchPage
import com.yinyue.player.data.source.model.TrackQuality
import java.util.zip.Inflater

/**
 * 酷我（kw）元数据源，逻辑移植自 lx-music-desktop 的 musicSdk/kw 模块：
 * 搜索 r.s 接口、N_MINFO 音质解析、mlyric yeelion 歌词解码、artistpicserver 封面。
 */
class KwProvider : SourceProvider {

    override val platform = "kw"
    override val displayName = "酷我"

    private val mInfoRx = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchPage {
        return searchWithRetry(keyword, page, limit, retryNum = 0)
    }

    private suspend fun searchWithRetry(keyword: String, page: Int, limit: Int, retryNum: Int): SearchPage {
        if (retryNum > 2) throw PlatformException("tryMaxNum", "kw search retry exhausted")
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${enc(keyword)}&pn=${page - 1}&rn=$limit" +
            "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1" +
            "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val resp = PlatformHttp.get(url)
        if (resp.statusCode != 200) throw PlatformException("http${resp.statusCode}", "kw search http ${resp.statusCode}")
        val root = JsonParser.parseString(resp.body).asJsonObject
        val total = root.get("TOTAL")?.asString ?: "0"
        val show = root.get("SHOW")?.asString ?: "0"
        // 对齐 lx：TOTAL!=0 且 SHOW==0 视为偶发空结果，重试
        if (total != "0" && show == "0") return searchWithRetry(keyword, page, limit, retryNum + 1)
        val list = parseAbsList(root.get("abslist"))
            ?: return searchWithRetry(keyword, page, limit, retryNum + 1) // N_MINFO 缺失等脏数据，重试
        val totalInt = total.toIntOrNull() ?: list.size
        return SearchPage(list, page, totalInt, ceil(totalInt, limit))
    }

    private fun parseAbsList(abslist: com.google.gson.JsonElement?): List<RawTrack>? {
        if (abslist == null || !abslist.isJsonArray) return null
        val result = ArrayList<RawTrack>(abslist.asJsonArray.size())
        for (item in abslist.asJsonArray) {
            val info = item.asJsonObject
            val nMinfo = info.get("N_MINFO")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val songId = info.get("MUSICRID")?.asString?.removePrefix("MUSIC_") ?: continue
            val qualitys = ArrayList<TrackQuality>()
            for (seg in nMinfo.split(';')) {
                val m = mInfoRx.find(seg) ?: continue
                val (bitrate, size) = m.groupValues[2] to m.groupValues[4]
                val type = when (bitrate) {
                    "4000" -> QualityType.FLAC24BIT
                    "2000" -> QualityType.FLAC
                    "320" -> QualityType.K320
                    "128" -> QualityType.K128
                    else -> null
                } ?: continue
                qualitys.add(TrackQuality(type, size))
            }
            qualitys.reverse() // 对齐 lx：types 高音质在前
            val durationSec = info.get("DURATION")?.asString?.toIntOrNull()
            result.add(
                RawTrack(
                    platform = platform,
                    remoteTrackId = songId,
                    songName = decodeName(info.get("SONGNAME")?.asString ?: ""),
                    artistName = formatSinger(decodeName(info.get("ARTIST")?.asString ?: "")),
                    albumName = info.get("ALBUM")?.takeIf { !it.isJsonNull }?.asString?.let(::decodeName),
                    albumId = info.get("ALBUMID")?.takeIf { !it.isJsonNull }?.asString?.let(::decodeName) ?: "",
                    durationMs = durationSec?.let { it * 1000L },
                    qualitys = qualitys,
                    extras = emptyMap(), // kw 无平台扩展字段（对齐 lx toOldMusicInfo）
                ),
            )
        }
        return result
    }

    override suspend fun lyric(remoteTrackId: String): String? {
        val url = "http://mlyric.kuwo.cn/mobi.s?f=web&type=lyric&lrcx=1&rid=$remoteTrackId&encode=utf8"
        val resp = PlatformHttp.get(url, wantRaw = true)
        if (resp.statusCode != 200) return null
        val text = KwLrcCodec.decode(resp.raw ?: return null, isGetLyricx = true)
        if (text.isEmpty()) return null
        return KwLrcCodec.parseLrc(text) // 解析失败（翻译占比异常等）返回 null
    }

    // ── 推荐歌单 / 歌单歌曲（酷我：中文资源为主，作为发现页的主力歌单源）──

    override suspend fun recommendPlaylists(order: String, offset: Int, limit: Int, cat: String): List<PlaylistBrief> {
        val page = (offset / limit.coerceAtLeast(1)) + 1
        val body = PlatformHttp.get(
            "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList" +
                "?pn=$page&rn=$limit&order=${if (order == "new") "new" else "hot"}" +
                "&pay=0&loginUid=0&loginSid=0&appUid=0",
        ).body ?: return emptyList()
        return runCatching {
            val arr = JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("data")?.getAsJsonArray("data")
                ?: return@runCatching emptyList<PlaylistBrief>()
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                PlaylistBrief(
                    platform = platform,
                    playlistId = id,
                    name = o.get("name")?.asString ?: "",
                    coverUrl = o.get("img")?.takeIf { !it.isJsonNull }?.asString,
                    playCount = o.get("listencnt")?.takeIf { !it.isJsonNull }?.asString?.toLongOrNull() ?: 0L,
                    trackCount = o.get("total")?.takeIf { !it.isJsonNull }?.asString?.toIntOrNull() ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistSongs(playlistId: String): List<RawTrack> {
        val body = PlatformHttp.get(
            "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId&pn=0&rn=500" +
                "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.1.0.2&newver=1",
        ).body ?: return emptyList()
        return runCatching {
            val arr = JsonParser.parseString(body).asJsonObject.getAsJsonArray("musiclist")
                ?: return@runCatching emptyList<RawTrack>()
            arr.mapNotNull { el -> parsePlaylistItem(el.asJsonObject) }
        }.getOrDefault(emptyList())
    }

    /** 歌单条目 → RawTrack（字段名与搜索接口不同：id/name/artist/MINFO/duration） */
    private fun parsePlaylistItem(info: JsonObject): RawTrack? {
        val songId = info.get("id")?.takeIf { !it.isJsonNull }?.asString?.removePrefix("MUSIC_") ?: return null
        val nMinfo = info.get("MINFO")?.takeIf { !it.isJsonNull }?.asString
            ?: info.get("N_MINFO")?.takeIf { !it.isJsonNull }?.asString
            ?: return null
        val qualitys = ArrayList<TrackQuality>()
        for (seg in nMinfo.split(';')) {
            val m = mInfoRx.find(seg) ?: continue
            val (bitrate, size) = m.groupValues[2] to m.groupValues[4]
            val type = when (bitrate) {
                "4000" -> QualityType.FLAC24BIT
                "2000" -> QualityType.FLAC
                "320" -> QualityType.K320
                "128" -> QualityType.K128
                else -> null
            } ?: continue
            qualitys.add(TrackQuality(type, size))
        }
        qualitys.reverse()
        val durationSec = info.get("duration")?.takeIf { !it.isJsonNull }?.asString?.toIntOrNull()
        return RawTrack(
            platform = platform,
            remoteTrackId = songId,
            songName = decodeName(info.get("name")?.asString ?: ""),
            artistName = formatSinger(decodeName(info.get("artist")?.asString ?: "")),
            albumName = info.get("album")?.takeIf { !it.isJsonNull }?.asString?.let(::decodeName),
            albumId = info.get("albumid")?.takeIf { !it.isJsonNull }?.asString?.let(::decodeName) ?: "",
            durationMs = durationSec?.let { it * 1000L },
            qualitys = qualitys,
            extras = emptyMap(),
        )
    }

    override suspend fun cover(remoteTrackId: String): String? {
        val url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$remoteTrackId"
        val resp = PlatformHttp.get(url)
        if (resp.statusCode != 200) return null
        val body = resp.body?.trim() ?: return null
        return if (body.startsWith("http")) body else null
    }

    companion object {
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /** lx formatSinger：& → 、 */
        fun formatSinger(raw: String): String = raw.replace("&", "、")

        /** lx decodeName 简化版：HTML 数字/命名实体还原 */
        fun decodeName(raw: String): String {
            if (!raw.contains('&')) return raw
            var s = raw
            for ((k, v) in mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ")) {
                s = s.replace(k, v)
            }
            // 数字实体
            val rx = Regex("&#(\\d+);")
            while (true) {
                val m = rx.find(s) ?: break
                s = s.replaceRange(m.range, m.groupValues[1].toInt().toChar().toString())
            }
            return s
        }

        fun ceil(total: Int, limit: Int): Int = if (total <= 0) 1 else (total + limit - 1) / limit
    }
}

/** kw 歌词解码：tp=content 前缀 → zlib inflate → (lrcx) base64 → 'yeelion' XOR → LRC 文本 */
object KwLrcCodec {
    private val timeExp = Regex("""\[([\d:.]+)]""")
    private val tagLineRx = Regex("""\[(ver|ti|ar|al|offset|by|kuwo):\s*(\S+(?:\s+\S+)*)\s*]""")
    private val wordTimeAllRx = Regex("""<(-?\d+),(-?\d+)(?:,-?\d+)?>""")
    private val lyricxTagRx = Regex("""^<-?\d+,-?\d+>""")

    fun decode(raw: ByteArray, isGetLyricx: Boolean): String {
        val prefix = String(raw, 0, minOf(10, raw.size), Charsets.UTF_8).lowercase()
        if (prefix != "tp=content") return ""
        val sep = indexOfCrlfCrlf(raw) ?: return ""
        val inflated = inflate(raw.copyOfRange(sep + 4, raw.size)) ?: return ""
        if (!isGetLyricx) return String(inflated, Charsets.UTF_8)
        val b64 = String(inflated, Charsets.UTF_8).trim()
        val decoded = tolerantBase64Decode(b64)
        val key = "yeelion".toByteArray(Charsets.UTF_8)
        val out = ByteArray(decoded.size)
        var i = 0
        var k = 0
        while (i < decoded.size) {
            out[i] = (decoded[i].toInt() xor key[k].toInt()).toByte()
            i++
            k = (k + 1) % key.size
        }
        return String(out, Charsets.UTF_8)
    }

    /**
     * 宽容版 base64：忽略非表内字符、不校验 padding，
     * 对齐 Node Buffer.from(str,'base64') 语义（XOR 后的字节可能含非表字符）。
     */
    private fun tolerantBase64Decode(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ByteArray(s.length * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        var n = 0
        for (c in s) {
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((acc shr bits) and 0xff).toByte()
            }
        }
        return out.copyOf(n)
    }

    private fun indexOfCrlfCrlf(raw: ByteArray): Int? {
        for (i in 0 until raw.size - 3) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return null
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

    /**
     * lx kw/lyric.js parseLrc 的 Kotlin 移植：重复时间行的首行进翻译轨，
     * 逐字时间标记剥离；解析失败返回 null（对齐 lx 的 failed 分支）。
     */
    fun parseLrc(lrcText: String): String? {
        val lines = lrcText.split(Regex("\\r\\n|\\r|\\n"))
        val tags = ArrayList<String>()
        data class Line(var time: String, val text: String)
        val lrcArr = ArrayList<Line>()
        for (raw in lines) {
            val line = raw.trim()
            val times = timeExp.findAll(line).toList()
            if (times.isNotEmpty()) {
                val text = line.replace(timeExp, "").replace(wordTimeAllRx, "").trim()
                var time = times.first().groupValues[1]
                if (Regex("\\.\\d\\d$").containsMatchIn(time)) time += "0"
                lrcArr.add(Line(time, text))
            } else if (tagLineRx.containsMatchIn(line)) {
                tags.add(line)
            }
        }
        // sortLrcArr：重复时间 → 旧行挪入翻译轨
        val seen = HashSet<String>()
        val lrc = ArrayList<Line>()
        val lrcT = ArrayList<Line>()
        var isLyricx = false
        for (item in lrcArr) {
            if (seen.contains(item.time)) {
                if (lrc.size < 2) continue
                val tItem = lrc.removeAt(lrc.size - 1)
                tItem.time = lrc[lrc.size - 1].time
                lrcT.add(tItem)
                lrc.add(item)
            } else {
                lrc.add(item)
                seen.add(item.time)
            }
            if (!isLyricx && lyricxTagRx.containsMatchIn(item.text)) isLyricx = true
        }
        if (!isLyricx && lrcT.size > lrc.size * 0.3 && lrc.size - lrcT.size > 6) return null
        val main = buildLrc(tags, lrc.map { it.time to it.text })
        if (!Regex("\\[\\d{1,2}:.*\\d{1,4}]").containsMatchIn(main)) return null
        return main
    }

    private fun buildLrc(tags: List<String>, lines: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        if (tags.isNotEmpty()) sb.append(tags.joinToString("\n")).append('\n')
        for ((time, text) in lines) {
            sb.append('[').append(time).append(']').append(text).append('\n')
        }
        return sb.toString()
    }
}
