package com.yinyue.player.data.fallback

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 抖音解析 adapter（候选 4）：把「短链 → aweme id → 媒体流」的网络与解析细节
 * 从 [FallbackImporter] 抽出来，导入模块只负责下载与落库。
 *
 * 对外 interface 只暴露 [resolve] 一个意图：给分享文案 / 链接，回答媒体流。
 * 多链路顺序（自建中继 → 分享页 RENDER_DATA/_ROUTER_DATA → 旧接口 → 移动播放端点），
 * 与各级失败原因都收在实现里。
 */
class DouyinResolver {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true) // 短链（v.douyin.com/xxx）需跟随跳转才能拿到含 id 的最终地址
        .cookieJar(MemoryCookieJar())
        .build()

    /** 分享文案 / 链接 → 媒体流；全部链路失败时抛出汇总原因 */
    fun resolve(shareUrl: String, relay: String): DouyinMedia {
        val rawUrl = DouyinShare.extractUrl(shareUrl)
            ?: throw IllegalStateException("没有找到链接：请粘贴抖音分享文案或视频链接")
        val id = resolveAwemeId(rawUrl)
        val sharedTitle = DouyinShare.extractTitle(shareUrl)
        val media = resolveMedia(relay, id, rawUrl)
        val titled = if (media.title == "抖音导入" && sharedTitle.isNotBlank()) {
            media.copy(title = sharedTitle)
        } else {
            media
        }
        return titled.copy(videoId = id)
    }

    /** 短链 → 最终地址 → aweme id；各阶段失败给不同提示 */
    private fun resolveAwemeId(rawUrl: String): String {
        if (!DouyinShare.isShortLink(rawUrl)) {
            return DouyinShare.parseId(rawUrl)
                ?: throw IllegalStateException("无法从链接解析视频 ID：$rawUrl")
        }
        val finalUrl = try {
            client.newCall(
                Request.Builder().url(rawUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("Referer", DOUYIN_REFERER)
                    .build(),
            ).execute().use { it.request.url.toString() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "短链跳转失败（${e.message?.take(40)}）：可改为粘贴在浏览器打开后的完整链接",
            )
        }
        return DouyinShare.parseId(finalUrl)
            ?: throw IllegalStateException("短链跳转后仍未取到视频 ID：$finalUrl")
    }

    private companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val DOUYIN_REFERER = "https://www.douyin.com/"

        /** 分享页候选（新版数据在 RENDER_DATA，旧版在 _ROUTER_DATA；两个域名都试，命中率更高） */
        val SHARE_PAGES = listOf(
            "https://www.iesdouyin.com/share/video/{id}/" to MOBILE_UA,
            "https://www.douyin.com/video/{id}" to DESKTOP_UA,
        )
    }
    /** 依序尝试各条链路，全部失败时把每一级的原因汇总抛出 */
    private fun resolveMedia(relay: String, id: String, rawUrl: String): DouyinMedia {
        val tried = mutableListOf<String>()
        // ① 自建/第三方解析中继（设置 → 进阶，可填多个、按顺序试；分享页被风控时这是唯一稳定路径）
        for (endpoint in DouyinParser.relayEndpoints(relay)) {
            val r = runCatching { fetchFromRelay(endpoint, id) }
            r.getOrNull()?.let { return it }
            tried += "$endpoint：${r.exceptionOrNull()?.message?.take(40) ?: "无数据"}"
        }
        // ② 分享页（新 RENDER_DATA / 旧 _ROUTER_DATA 一次请求两种结构都试）
        bootstrapCookies()
        for ((template, ua) in SHARE_PAGES) {
            val url = template.replace("{id}", id)
            val html = try {
                getText(url, ua)
            } catch (e: Exception) {
                tried += "$url 请求失败（${e.message?.take(30)}）"
                null
            } ?: continue
            DouyinParser.parseShareHtml(html)?.let { return it }
            // 区分"被风控（返回的不是数据页）"与"结构又变了（是数据页但没媒体字段）"
            val isDataPage = DouyinParser.hasRenderData(html) || DouyinParser.hasRouterData(html)
            tried += if (isDataPage) {
                "$url 页面结构已变（无媒体字段）"
            } else {
                "$url 被风控拦截（返回验证页，非数据页）"
            }
        }
        // ③ 旧 iteminfo 接口（已废弃，仅作最后兜底）
        val infoUrl = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$id"
        val infoBody = try {
            getText(infoUrl, DESKTOP_UA)
        } catch (e: Exception) {
            null
        }
        if (infoBody.isNullOrBlank()) {
            tried += "旧接口无返回（该接口已废弃）"
        } else {
            DouyinParser.parseItemInfo(infoBody)?.let { return it }
            tried += "旧接口无媒体数据"
        }
        // ④ 移动播放端点：可能返回 302 CDN、200 媒体或 200 空文本（风控）。
        // 这里必须验证媒体首字节，不能把 HTTP 200 当成功。
        val playUrl = "https://www.iesdouyin.com/aweme/v1/play/?aweme_id=$id"
        try {
            client.newCall(
                Request.Builder().url(playUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("Referer", DOUYIN_REFERER)
                    .header("Accept", "*/*")
                    .build(),
            ).execute().use { r ->
                val loc = r.header("location")
                if (!loc.isNullOrBlank() && r.code in 300..399) {
                    val finalHost = runCatching { loc.toHttpUrlOrNull()?.host ?: "" }.getOrDefault("")
                    val isCdn = finalHost.isNotBlank() && !finalHost.endsWith("douyin.com") &&
                        !finalHost.endsWith("iesdouyin.com")
                    if (isCdn && probeMedia(loc)) {
                        return DouyinMedia(audioUrl = loc, videoUrl = null, title = "抖音导入", author = "", videoId = id)
                    }
                    tried += if (isCdn) "移动播放端点 CDN 首包失败" else "移动播放端点仍回环播放页：$loc"
                } else {
                    if (probeMedia(playUrl)) {
                        return DouyinMedia(audioUrl = playUrl, videoUrl = null, title = "抖音导入", author = "", videoId = id)
                    }
                    tried += "移动播放端点不可用（HTTP ${r.code}，200 空响应视为风控）"
                }
            }
        } catch (e: Exception) {
            tried += "移动播放端点失败（${e.message?.take(35)}）"
        }
        throw IllegalStateException(
            "抖音解析失败：${tried.joinToString("；")}。" +
                "官方分享页已启用风控拦截。稳定做法：在 设置 → 进阶 → 解析中继地址 填一个可用的解析服务" +
                "（可填多个，逗号分隔；自建时只需让 {中继}/{视频ID} 返回一行音频直链即可）",
        )
    }

    /** 媒体链路探测：只接受 200/206、媒体 Content-Type 且首包有字节。 */
    private fun probeMedia(url: String): Boolean = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", MOBILE_UA)
                .header("Referer", DOUYIN_REFERER)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-1023")
                .build(),
        ).execute().use { r ->
            val contentType = r.header("Content-Type").orEmpty()
            val mediaType = contentType.contains("video/", ignoreCase = true) ||
                contentType.contains("audio/", ignoreCase = true) ||
                contentType.contains("application/octet-stream", ignoreCase = true)
            mediaType && r.isSuccessful && r.body?.byteStream()?.read() != -1
        }
    }.getOrDefault(false)

    /**
     * 取中继：优先 `{中继}/{id}`，失败再试 `{中继}?id={id}`（两种最简契约都兼容）。
     * 返回体交给 [DouyinParser.parseRelayBody] 容错解析（纯文本直链 / JSON / 包一层 data 都认）。
     */
    private fun fetchFromRelay(relay: String, id: String): DouyinMedia {
        val urls = listOf("$relay/$id", "$relay?id=$id")
        var lastError = "无数据"
        for (url in urls) {
            val body = try {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", MOBILE_UA).build(),
                ).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                lastError = e.message?.take(40) ?: "请求失败"
                continue
            }
            if (body.isBlank()) {
                lastError = "返回空"
                continue
            }
            DouyinParser.parseRelayBody(body)?.let { return it }
            lastError = "返回体无法识别为直链"
        }
        throw IllegalStateException(lastError)
    }

    /** 访问主站拿 ttwid/__ac_nonce 等匿名 Cookie（失败不影响后续链路） */
    private fun bootstrapCookies() {
        runCatching {
            client.newCall(
                Request.Builder().url("https://www.douyin.com/")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build(),
            ).execute().close()
        }
    }

    private fun getText(url: String, ua: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", DOUYIN_REFERER)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /**
     * 媒体落盘（网络部分留在 adapter）：抖音媒体直链必须带 Referer，否则 403。
     * 失败一律抛错（此前静默吞掉会让用户以为导入成功但文件是空的）。
     */
    fun download(url: String, target: File) {
        try {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", MOBILE_UA)
                    .header("Referer", DOUYIN_REFERER)
                    .header("Accept", "*/*")
                    .build(),
            ).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val body = r.body ?: throw IllegalStateException("响应为空")
                body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        } catch (e: Exception) {
            target.delete()
            throw IllegalStateException(
                "媒体下载失败（${e.message?.take(50)}）：直链有时效或该作品需登录，可重试或配置中继",
            )
        }
        if (!target.exists() || target.length() == 0L) {
            target.delete()
            throw IllegalStateException("媒体下载失败：文件为空（直链可能已过期），可重试或配置中继")
        }
        if (isLikelyHtml(target)) {
            target.delete()
            throw IllegalStateException("媒体下载失败：返回验证页（直链被风控），可配置中继后重试")
        }
    }

    /** 下载后校验魔数，防止 HTML/JSON 风控响应被当成媒体文件。 */
    private fun isLikelyHtml(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(128)
            val n = input.read(head)
            val text = String(head, 0, maxOf(n, 0), Charsets.UTF_8).lowercase()
            text.contains("<!doctype html") || text.contains("<html") || text.trimStart().startsWith("{")
        }
    }.getOrDefault(false)

    /** 极简内存 Cookie 罐（抖音会话内保持匿名 Cookie 即可，不落盘） */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val list = store.getOrPut(url.host) { java.util.Collections.synchronizedList(mutableListOf()) }
            synchronized(list) {
                cookies.forEach { c ->
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.let { list -> synchronized(list) { list.filter { it.matches(url) } } }
                ?: emptyList()
    }
}
