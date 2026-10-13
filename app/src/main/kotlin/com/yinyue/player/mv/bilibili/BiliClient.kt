package com.yinyue.player.mv.bilibili

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** B 站接口访问异常 */
class BiliClientException(message: String) : RuntimeException(message)

/**
 * B 站 MV 主线的基础客户端：
 * nav 取 WBI 密钥 / wbi 搜索（tid=3 音乐区）/ view+playurl 取 DASH 双流。
 * 全部网络方法为 suspend+IO（避免 NetworkOnMainThreadException）。
 */
class BiliClient(private val client: OkHttpClient = OkHttpClient()) {

    data class WbiKeys(val imgKey: String, val subKey: String)

    /** 搜索候选视频（Task 17 过滤链的输入） */
    data class BiliVideoItem(
        val bvid: String,
        val title: String,
        val description: String,
        val durationSec: Long,
        val author: String,
        val mid: Long,
        val play: Long,
        val officialVerify: Boolean,
        val type: String,
        val coverUrl: String? = null,
    )

    data class SearchResult(val code: Int, val message: String, val numResults: Int, val firstTitle: String?)

    /** buvid Cookie（内存缓存；持久化归 Task 16 DataStore） */
    @Volatile
    private var cachedCookie: String? = null

    /** 冷启动访问 B 站首页一次收 buvid3/buvid4（-352 风控规避，v3.0 §5.5） */
    suspend fun fetchBuvidCookie(budgetMs: Long = 0L): String? {
        cachedCookie?.let { return it }
        return withContext(Dispatchers.IO) {
            if (budgetMs in 1..500 && !BiliRateLimiter.acquireWithBudget(budgetMs)) return@withContext null
            runCatching {
                client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
                    .newCall(
                        Request.Builder()
                            .url("https://www.bilibili.com/")
                            .header("User-Agent", WbiSigner.USER_AGENT)
                            .build(),
                    )
                    .execute()
                    .use { resp ->
                        val cookies = resp.headers("set-cookie")
                        val b3 = cookies.firstNotNullOfOrNull { Regex("buvid3=([^;]+)").find(it)?.groupValues?.get(1) }
                        val b4 = cookies.firstNotNullOfOrNull { Regex("buvid4=([^;]+)").find(it)?.groupValues?.get(1) }
                        listOfNotNull(
                            b3?.let { "buvid3=$it" },
                            b4?.let { "buvid4=$it" },
                        ).joinToString("; ").ifEmpty { null }
                    }
            }.getOrNull()
        }.also { if (it != null) cachedCookie = it }
    }

    /** 请求携带的 Cookie：用户自备（若有）+ buvid（风控规避），两者合并 */
    private fun effectiveCookie(): String? {
        val buvid = cachedCookie
        val user = BiliRateLimiter.Config.userCookie?.takeIf { it.isNotBlank() }
        return when {
            user != null && buvid != null -> "$user; $buvid"
            user != null -> user
            else -> buvid
        }
    }

    private suspend fun get(url: String): String {
        BiliRateLimiter.acquire() // 全局令牌桶：所有 B站请求串行化（间隔可在设置中调慢，下限 1.5s）
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", WbiSigner.USER_AGENT)
                .header("Referer", WbiSigner.REFERER)
            effectiveCookie()?.let { builder.header("Cookie", it) }
            try {
                client.newBuilder()
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(builder.build())
                    .execute()
                    .use { resp ->
                        if (!resp.isSuccessful) throw BiliClientException("HTTP ${resp.code}")
                        resp.body?.string() ?: throw BiliClientException("empty body")
                    }
            } catch (e: BiliClientException) {
                throw e
            } catch (e: Exception) {
                // 带出异常类型与细节，供 UI 诊断标注
                throw BiliClientException("${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
            }
        }
    }

    suspend fun fetchWbiKeys(budgetMs: Long = 0L): WbiKeys {
        if (budgetMs in 1..1_000 && !BiliRateLimiter.acquireWithBudget(budgetMs)) {
            throw BiliClientException("playback budget exceeded before nav")
        }
        val body = get("https://api.bilibili.com/x/web-interface/nav")
        val root = JsonParser.parseString(body).asJsonObject
        val data = root.getAsJsonObject("data")
            ?: throw BiliClientException("nav 响应缺少 data: code=${root.get("code")}")
        val wbi = data.getAsJsonObject("wbi_img")
            ?: throw BiliClientException("nav 响应缺少 wbi_img")
        val img = wbi.get("img_url").asString.substringAfterLast("/").substringBefore(".")
        val sub = wbi.get("sub_url").asString.substringAfterLast("/").substringBefore(".")
        return WbiKeys(img, sub)
    }

    suspend fun searchVideo(keyword: String, wbi: WbiKeys, page: Int = 1): SearchResult {
        val url = signedSearchUrl(keyword, wbi, page)
        val body = get(url)
        val root = JsonParser.parseString(body).asJsonObject
        val code = root.get("code").asInt
        val message = root.get("message")?.takeIf { !it.isJsonNull }?.asString ?: ""
        if (code != 0) return SearchResult(code, message, 0, null)
        val data = root.getAsJsonObject("data")
        val result = data?.getAsJsonArray("result")
        return SearchResult(
            code = 0,
            message = message,
            numResults = result?.size() ?: data?.get("numResults")?.asInt ?: 0,
            firstTitle = result?.firstOrNull()?.asJsonObject?.get("title")?.asString,
        )
    }

    /** 播放地址（Task 18）：pagelist 取 cid（比 view 风控更松）→ playurl 取 DASH 双流（匿名 480P） */
    data class MvPlayInfo(val videoUrl: String?, val audioUrl: String, val durationMs: Long, val quality: String)

    suspend fun resolvePlayUrl(bvid: String, budgetMs: Long = 0L): MvPlayInfo {
        val budget = (budgetMs - 1).coerceAtLeast(0L)
        // pagelist：风控比 view 宽松，且一次拿到 cid + 时长
        if (budget in 1..2_000 && !BiliRateLimiter.acquireWithBudget(budget)) {
            throw BiliClientException("playback budget exceeded before pagelist")
        }
        val pageBody = get("https://api.bilibili.com/x/player/pagelist?bvid=${WbiSigner.urlEncode(bvid)}&jsonp=jsonp")
        val pageRoot = JsonParser.parseString(pageBody).asJsonObject
        if (pageRoot.get("code").asInt != 0) throw BiliClientException("pagelist code=${pageRoot.get("code")}")
        val firstPage = pageRoot.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
            ?: throw BiliClientException("pagelist empty")
        val cid = firstPage.get("cid")?.asLong ?: throw BiliClientException("pagelist missing cid")
        val durationMs = firstPage.get("duration")?.takeIf { !it.isJsonNull }?.asLong?.times(1000) ?: 0L

        // fnval=16 才返回 DASH（音视频分离）；qn 由画质偏好决定（匿名上限 480P，带 Cookie 可到 1080P）
        val qn = BiliRateLimiter.Config.qn()
        if (budget in 1..4_000 && !BiliRateLimiter.acquireWithBudget(budget)) {
            throw BiliClientException("playback budget exceeded before playurl")
        }
        val playBody = get(
            "https://api.bilibili.com/x/player/playurl?bvid=${WbiSigner.urlEncode(bvid)}&cid=$cid" +
                "&qn=$qn&fnval=16&fnver=0&fourk=1&otype=json",
        )
        val playRoot = JsonParser.parseString(playBody).asJsonObject
        val data = playRoot.getAsJsonObject("data") ?: throw BiliClientException("playurl missing data")
        val dash = data.getAsJsonObject("dash")
        if (dash != null) {
            val audioObj = dash.getAsJsonArray("audio")?.firstOrNull()?.asJsonObject
                ?: throw BiliClientException("playurl missing audio stream")
            val candidates = ArrayList<String>(4)
            audioObj.get("baseUrl")?.asString?.let { candidates.add(it) }
            audioObj.getAsJsonArray("backupUrl")?.forEach { if (it.isJsonPrimitive) candidates.add(it.asString) }
            val audio = pickStableUrl(candidates)
                ?: throw BiliClientException("playurl audio url empty")
            val videoObj = dash.getAsJsonArray("video")?.firstOrNull()?.asJsonObject
            val video = videoObj?.let {
                pickStableUrl(
                    buildList {
                        it.get("baseUrl")?.asString?.let { u -> add(u) }
                        it.getAsJsonArray("backupUrl")?.forEach { b -> if (b.isJsonPrimitive) add(b.asString) }
                    },
                )
            }
            val quality = videoObj?.get("id")?.asString ?: "480P"
            return MvPlayInfo(video, audio, durationMs, quality)
        }
        // 兼容 durl（音视频合一 mp4）：作为兜底播放源
        val durl = data.getAsJsonArray("durl")?.firstOrNull()?.asJsonObject?.get("url")?.asString
            ?: throw BiliClientException("playurl 无 dash 也无 durl")
        return MvPlayInfo(null, durl, durationMs, "durl")
    }

    /**
     * 选稳定 CDN：优先 upos-（官方 CDN），其次其他 https，最后 mcdn（P2P，移动端/模拟器常不可达）。
     */
    private fun pickStableUrl(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        fun score(u: String): Int = when {
            u.contains("upos-") -> 0
            u.contains("mcdn") || u.contains(":8082") -> 3
            u.startsWith("https://") -> 1
            else -> 2
        }
        return candidates.minByOrNull { score(it) }
    }

    /** 搜索候选（供过滤链），-352 等风控码抛 BiliClientException 交上层退避 */
    suspend fun searchVideoCandidates(keyword: String, wbi: WbiKeys, page: Int = 1): List<BiliVideoItem> {
        val url = signedSearchUrl(keyword, wbi, page)
        val body = get(url)
        val root = JsonParser.parseString(body).asJsonObject
        val code = root.get("code").asInt
        if (code != 0) throw BiliClientException("bili code=$code ${root.get("message")?.asString ?: ""}")
        val items = ArrayList<BiliVideoItem>()
        val result = root.getAsJsonObject("data")?.getAsJsonArray("result") ?: return items
        for (item in result) {
            val o = item.asJsonObject
            val type = o.get("type")?.asString ?: continue
            val bvid = o.get("bvid")?.asString ?: continue
            items.add(
                BiliVideoItem(
                    bvid = bvid,
                    title = stripHtml(o.get("title")?.asString ?: ""),
                    description = stripHtml(o.get("description")?.asString ?: ""),
                    durationSec = parseDuration(o.get("duration")),
                    author = o.get("author")?.asString ?: "",
                    mid = o.get("mid")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    play = o.get("play")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    officialVerify = o.get("official_verify")?.takeIf { !it.isJsonNull }?.asInt == 1,
                    type = type,
                    coverUrl = o.get("pic")?.asString?.let { pic ->
                        if (pic.startsWith("//")) "https:$pic" else pic
                    },
                ),
            )
        }
        return items
    }

    /** 剥离 B站搜索返回的关键词高亮标签（<em class="keyword">…</em>）与 HTML 实体 */
    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    /** duration 兼容两种格式：数字秒 / "MM:SS"（web 搜索接口返回字符串） */
    private fun parseDuration(el: com.google.gson.JsonElement?): Long = when {
        el == null || el.isJsonNull -> 0L
        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong
        el.isJsonPrimitive -> el.asString.split(":")
            .fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: 0L) }
        else -> 0L
    }

    private fun signedSearchUrl(keyword: String, wbi: WbiKeys, page: Int): String {
        val signed = WbiSigner.sign(
            params = mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to page.toString(),
                "tid" to "3", // 音乐区（v3.0 §5.3）
            ),
            imgKey = wbi.imgKey,
            subKey = wbi.subKey,
            wts = System.currentTimeMillis() / 1000,
        )
        return "https://api.bilibili.com/x/web-interface/wbi/search/type?" +
            signed.entries.joinToString("&") { (k, v) -> "${WbiSigner.urlEncode(k)}=${WbiSigner.urlEncode(v)}" }
    }
}
