package com.yinyue.player.data.source

import com.yinyue.player.data.source.model.SearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 平台请求失败（kind 对齐 lx request.js 的错误归一化） */
class PlatformException(val kind: String, message: String) : Exception("[$kind] $message")

object PlatformErrors {
    fun normalize(e: Exception): String = when {
        e is SocketTimeoutException -> "timeout"
        e is UnknownHostException -> "notConnectNetwork"
        e.message?.contains("hang up", ignoreCase = true) == true -> "unachievable"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/**
 * 音源平台共用 HTTP（对齐 lx musicSdk/options.js 约定：
 * UA 'lx-music request' 与 bHh 头；15s 默认超时）。
 */
object PlatformHttp {
    const val UA = "lx-music request"
    const val BHH = "624868746c"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Resp(val statusCode: Int, val body: String?, val raw: ByteArray?)

    suspend fun get(
        url: String,
        headers: Map<String, String> = mapOf("User-Agent" to UA, BHH to "[$BHH]"),
        wantRaw: Boolean = false,
    ): Resp = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val raw = if (wantRaw) resp.body?.bytes() else null
                val body = if (wantRaw) null else resp.body?.string()
                Resp(resp.code, body, raw)
            }
        } catch (e: Exception) {
            throw PlatformException(PlatformErrors.normalize(e), url)
        }
    }

    suspend fun post(
        url: String,
        body: String,
        contentType: String = "application/x-www-form-urlencoded",
        headers: Map<String, String> = emptyMap(),
    ): Resp = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.post(body.toRequestBody(contentType.toMediaType()))
        try {
            client.newCall(builder.build()).execute().use { resp ->
                Resp(resp.code, resp.body?.string(), null)
            }
        } catch (e: Exception) {
            throw PlatformException(PlatformErrors.normalize(e), url)
        }
    }
}

/**
 * 内置元数据源抽象（歌曲主线）：搜索 / 歌词 / 封面 /（时长在搜索结果内）。
 * 平台官方原唱标签按"尽力而为"接入（开放问题4）。
 */
interface SourceProvider {
    val platform: String
    val displayName: String
    suspend fun search(keyword: String, page: Int, limit: Int = 30): SearchPage
    suspend fun lyric(remoteTrackId: String): String?
    suspend fun cover(remoteTrackId: String): String?

    // ── 以下为可选能力（排行榜 / 推荐歌单），平台不支持时保持默认空实现 ──

    /** 平台内置榜单列表（对齐落雪各平台 leaderboard 的内置榜单） */
    suspend fun topLists(): List<com.yinyue.player.data.source.model.TopListBoard> = emptyList()

    /** 榜单内的歌曲 */
    suspend fun topListSongs(boardId: String): List<com.yinyue.player.data.source.model.RawTrack> = emptyList()

    /**
     * 推荐歌单：order = hot（最热）/ new（最新）；cat = 分类名（平台支持时生效，空 = 全部）。
     * 分类很关键：默认「全部」里欧美内容占比高，用户选了「华语 / 古风 / 民谣」之后推荐才贴合口味。
     */
    suspend fun recommendPlaylists(
        order: String,
        offset: Int,
        limit: Int = 30,
        cat: String = "",
    ): List<com.yinyue.player.data.source.model.PlaylistBrief> = emptyList()

    /** 平台支持的歌单分类（空 = 不支持分类，界面不显示筛选条） */
    suspend fun playlistTags(): List<String> = emptyList()

    /** 歌单内的歌曲 */
    suspend fun playlistSongs(playlistId: String): List<com.yinyue.player.data.source.model.RawTrack> = emptyList()
}

/** 已注册的元数据源集合（kw/kg/tx/wy/mg 随 6a~6d 完成逐个注册） */
object SourceRegistry {
    private val providers = linkedMapOf<String, SourceProvider>()

    fun register(provider: SourceProvider) {
        providers[provider.platform] = provider
    }

    fun all(): List<SourceProvider> = providers.values.toList()

    fun get(platform: String): SourceProvider? = providers[platform]

    fun size(): Int = providers.size
}
