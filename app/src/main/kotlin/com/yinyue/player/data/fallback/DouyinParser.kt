package com.yinyue.player.data.fallback

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 抖音解析结果：音频流 + 视频流（MV 要有画面） */
data class DouyinMedia(
    val audioUrl: String,
    val videoUrl: String?,
    val title: String,
    val author: String,
    /** aweme id：落盘文件名与去重键都用它（解析层产出，导入层不再自己解析） */
    val videoId: String = "",
)

/**
 * 抖音响应解析（纯函数、无网络无副作用，便于单测钉住各种响应结构）。
 *
 * 背景（实测 2026-09）：官方两个老接口已不可用 ——
 * ① web/api/v2/aweme/iteminfo 返回 HTTP 200 但 body 为 0 字节（接口已废弃）；
 * ② 分享页 _ROUTER_DATA 结构改版，视频信息不再内联，改为前端带签名异步拉取。
 * 所以解析做成多结构兼容、能拿到就用：
 *   - RENDER_DATA：新版分享页 script id=RENDER_DATA（URI 编码的 JSON）
 *   - _ROUTER_DATA：旧版分享页 window._ROUTER_DATA（保留兼容）
 *   - iteminfo：旧版接口 [{"item": {...}}]（保留兼容）
 *
 * 三种结构里条目（item）字段形状基本一致，统一走 [fromItem]。
 */
object DouyinParser {

    /** 解析分享页 HTML：先试 RENDER_DATA（新），再试 _ROUTER_DATA（旧）。null = 这页没有可用信息 */
    fun parseShareHtml(html: String): DouyinMedia? {
        for (json in listOfNotNull(extractRenderDataJson(html), extractRouterDataJson(html))) {
            val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: continue
            val item = findItem(root) ?: continue
            fromItem(item)?.let { return it }
        }
        return null
    }

    /** 分享页是否带 RENDER_DATA（失败时用于区分被风控还是结构又变了） */
    fun hasRenderData(html: String): Boolean = extractRenderDataJson(html) != null

    /** 分享页是否带 _ROUTER_DATA（旧结构标记） */
    fun hasRouterData(html: String): Boolean = extractRouterDataJson(html) != null

    /** 新版分享页：script id=RENDER_DATA，内容多为 URI 编码 */
    fun extractRenderDataJson(html: String): String? {
        val marker = Regex("""<script[^>]*id=["']RENDER_DATA["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val start = marker.range.last + 1
        val end = html.indexOf("</script>", start)
        if (end <= start) return null
        val raw = html.substring(start, end).trim()
        if (raw.isEmpty()) return null
        // 少数页面直接给未编码 JSON；未编码时不解码（URLDecoder 会把 + 变成空格，破坏 JSON）
        if (raw.startsWith("{")) return raw
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
    }

    /** 旧版分享页：window._ROUTER_DATA = {...}（到 </script> 结束，末尾可能带分号） */
    fun extractRouterDataJson(html: String): String? {
        val marker = "window._ROUTER_DATA = "
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val rest = html.substring(idx + marker.length)
        val end = rest.indexOf("</script>")
        val body = (if (end > 0) rest.substring(0, end) else rest).trim().trimEnd(';').trim()
        return body.takeIf { it.startsWith("{") }
    }

    /** 旧版接口 web/api/v2/aweme/iteminfo：[{"item": {...}}]，也兼容直接给条目数组 */
    fun parseItemInfo(json: String): DouyinMedia? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (root.isJsonArray) {
            for (el in root.asJsonArray) {
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val item = obj.objOrNull("item") ?: obj
                fromItem(item)?.let { return it }
            }
            return null
        }
        if (root.isJsonObject) {
            val obj = root.asJsonObject
            findItem(obj)?.let { item -> fromItem(item)?.let { return it } }
            return fromItem(obj.objOrNull("item") ?: obj)
        }
        return null
    }

    /**
     * 广度优先在 JSON 树里找第一个真的能取出媒体地址的条目。
     * RENDER_DATA 顶层键随版本变化（app / video_(id)/page 等），固定路径必然失效，故用搜索；
     * 识别标准是能取到 URL 而不是有某个键，避免命中无关配置对象。
     */
    fun findItem(root: JsonElement): JsonObject? {
        val queue = ArrayDeque<JsonElement>()
        queue.addLast(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 50_000) {
            val el = queue.removeFirst()
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                if (hasMedia(obj)) return obj
                // 标准位置优先：item_list 的第一个条目
                val list = obj.get("item_list")?.takeIf { it.isJsonArray }?.asJsonArray
                if (list != null) {
                    for (i in 0 until list.size()) {
                        val c = list.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        if (hasMedia(c)) return c
                    }
                }
                obj.entrySet().forEach { (_, v) ->
                    if (v.isJsonObject || v.isJsonArray) queue.addLast(v)
                }
            } else if (el.isJsonArray) {
                el.asJsonArray.forEach {
                    if (it.isJsonObject || it.isJsonArray) queue.addLast(it)
                }
            }
        }
        return null
    }

    /** 条目 → 媒体信息；音频优先原声（music.play_addr，无水印） */
    fun fromItem(item: JsonObject): DouyinMedia? {
        val title = item.get("desc")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: "抖音导入"
        val author = item.objOrNull("author")
            ?.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString
            ?: item.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString
            ?: ""
        val audio = musicUrls(item).firstOrNull().orEmpty()
        val video = videoUrls(item).firstOrNull()
        if (audio.isBlank() && video.isNullOrBlank()) return null
        return DouyinMedia(audioUrl = audio, videoUrl = video, title = title, author = author)
    }

    /** 该对象能否取出媒体地址（作为是不是视频条目的判据） */
    fun hasMedia(obj: JsonObject): Boolean =
        musicUrls(obj).isNotEmpty() || videoUrls(obj).isNotEmpty()

    /** 原声音频候选（按优先级） */
    fun musicUrls(item: JsonObject): List<String> {
        val music = item.objOrNull("music") ?: return emptyList()
        val out = mutableListOf<String>()
        listOf("play_addr", "play_addr_hq", "play_url", "playAddrHq", "playAddr").forEach { key ->
            firstUrl(music.objOrNull(key))?.let { out.add(it) }
        }
        return out.distinct()
    }

    /** 视频候选（按优先级；playwm → play 去水印） */
    fun videoUrls(item: JsonObject): List<String> {
        val video = item.objOrNull("video") ?: return emptyList()
        val out = mutableListOf<String>()
        listOf("play_addr", "play_addr_hq", "download_addr", "playApi").forEach { key ->
            firstUrl(video.objOrNull(key))?.let { out.add(it) }
        }
        // 新结构：清晰度分档在 bit_rate 数组里（按码率降序，优先高码率）
        val rates = video.get("bit_rate")?.takeIf { it.isJsonArray }?.asJsonArray
        if (rates != null) {
            rates.mapNotNull { r -> r.takeIf { it.isJsonObject }?.asJsonObject }
                .sortedByDescending { br -> br.get("bit_rate")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L }
                .forEach { br -> firstUrl(br.objOrNull("play_addr"))?.let { out.add(it) } }
        }
        return out.distinct().map { it.replace("playwm", "play") }
    }

    // ── 解析中继（用户自建/第三方便捷解析服务）──

    /**
     * 中继地址列表：设置里可用逗号/分号/换行填多个，按顺序依次尝试。
     * 分享页被官方风控拦截时，中继是唯一稳定路径，所以允许多个（互相兜底）。
     */
    fun relayEndpoints(setting: String): List<String> =
        setting.split(',', '，', '\n', '\r', ';', '；')
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()

    /**
     * 中继返回体解析（尽量容错，方便用户自建极简中继）：
     * ① 纯文本直链：返回体就是一行 http(s) 地址 —— 最简契约，自建中继只要 `return url` 即可；
     * ② JSON（常见字段名都认）：audio_url/audio/music_url/url → 音频；video_url/video/play_url → 画面；
     * ③ JSON 外层再包一层 data/result，或返回数组 —— 一并兼容。
     */
    fun parseRelayBody(body: String): DouyinMedia? {
        val text = body.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return DouyinMedia(audioUrl = text, videoUrl = null, title = "抖音导入", author = "")
        }
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        val obj = when {
            root.isJsonObject -> {
                val o = root.asJsonObject
                o.objOrNull("data")
                    ?: o.objOrNull("result")
                    ?: o.objOrNull("item")
                    ?: o.objOrNull("aweme_detail")
                    ?: o.objOrNull("music")
                    ?: o
            }
            root.isJsonArray -> root.asJsonArray.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
            else -> null
        } ?: return null
        val audio = firstString(
            obj, "audio_url", "audio", "music_url", "music_play_url", "url", "play_url",
        ) ?: obj.objOrNull("music")?.let {
            firstString(it, "audio_url", "music_url", "music_play_url", "url", "play_url")
        }
        val video = firstString(
            obj, "video_url", "video", "nwm_video_url", "no_watermark_url", "play_addr",
        ) ?: obj.objOrNull("video")?.let {
            firstString(it, "video_url", "nwm_video_url", "no_watermark_url", "play_addr")
        }
        if (audio.isNullOrBlank() && video.isNullOrBlank()) return null
        return DouyinMedia(
            audioUrl = audio.orEmpty(),
            videoUrl = video,
            title = firstString(obj, "desc", "title", "song_name", "name")
                ?: obj.objOrNull("music")?.let { firstString(it, "title", "desc", "name") }
                ?: "抖音导入",
            author = firstString(obj, "author", "nickname", "up_name")
                ?: obj.objOrNull("author")?.let { firstString(it, "nickname", "name", "unique_id") }
                ?: "",
        )
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            if (!v.isNullOrBlank()) return v
        }
        return null
    }
    private fun firstUrl(addr: JsonObject?): String? = addr
        ?.get("url_list")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.firstOrNull { it.isJsonPrimitive }
        ?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.objOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
