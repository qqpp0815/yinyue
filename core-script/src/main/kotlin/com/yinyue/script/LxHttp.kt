package com.yinyue.script

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** 脚本内 lx.request 的响应结构，错误归一化对齐 lx-music-desktop request.js */
data class LxHttpResponse(
    val statusCode: Int,
    /** HTTP 状态短语（落雪 resp.statusMessage；脚本会用它做错误提示） */
    val statusMessage: String = "",
    val headers: Map<String, String>,
    val body: String?,
    val err: String?,
)

data class LxRequestOptions(
    val method: String = "GET",
    val timeoutMs: Long = 15_000,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** 错误归一化：ETIMEDOUT→timeout、ENOTFOUND→notConnectNetwork、socket hang up→unachievable */
object LxErrors {
    fun normalize(e: Exception): String = when {
        e is SocketTimeoutException -> "timeout"
        e is UnknownHostException -> "notConnectNetwork"
        e.message?.contains("hang up", ignoreCase = true) == true -> "unachievable"
        else -> e.message ?: e.javaClass.simpleName
    }
}

object LxHttp {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 同步执行脚本发起的 HTTP 请求（QuickJS 引擎为单线程语义，阻塞式最简单可靠；
     * 每个脚本一个独立引擎实例，互不影响）。
     */
    fun request(url: String, optionsJson: String?, allowedHosts: Set<String>): LxHttpResponse {
        val host = runCatching { URI(url).host }.getOrNull()
        if (allowedHosts.isNotEmpty() && (host == null || host !in allowedHosts)) {
            return LxHttpResponse(0, "", emptyMap(), null, "domain not allowed: $host")
        }
        val options = LxRequestOptionsParser.parse(optionsJson)
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) yinyue-player/0.1")
            options.headers.forEach { (k, v) -> builder.header(k, v) }
            val hasBody = options.body != null && options.method != "GET"
            when {
                options.method == "GET" -> builder.get()
                hasBody -> builder.method(options.method, options.body!!.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                else -> builder.method(options.method, null)
            }
            val callClient = baseClient.newBuilder()
                .callTimeout(options.timeoutMs.coerceAtMost(60_000), TimeUnit.MILLISECONDS)
                .build()
            callClient.newCall(builder.build()).execute().use { resp ->
                LxHttpResponse(
                    statusCode = resp.code,
                    statusMessage = resp.message ?: "",
                    headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" },
                    body = resp.body?.string(),
                    err = null,
                )
            }
        } catch (e: Exception) {
            LxHttpResponse(0, "", emptyMap(), null, LxErrors.normalize(e))
        }
    }
}

object LxRequestOptionsParser {
    fun parse(json: String?): LxRequestOptions {
        if (json.isNullOrBlank()) return LxRequestOptions()
        return runCatching {
            val o = JsonParser.parseString(json).asJsonObject
            val method = o.get("method")?.takeIf { it.isJsonPrimitive }?.asString?.uppercase() ?: "GET"
            val timeout = o.get("timeout")?.takeIf { it.isJsonPrimitive }?.asNumber?.toLong() ?: 15_000L
            val headers = o.getAsJsonObject("headers")?.entrySet()
                ?.associate { it.key to it.value.asString } ?: emptyMap()
            val formBody = o.getAsJsonObject("form")?.entrySet()
                ?.joinToString("&") { (k, v) -> "${formEnc(k)}=${formEnc(v.asString)}" }
            val body = o.get("body")?.takeIf { it.isJsonPrimitive }?.asString ?: formBody
            LxRequestOptions(method, timeout.coerceAtMost(60_000), headers, body)
        }.getOrDefault(LxRequestOptions())
    }

    private fun formEnc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    fun formDecode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8)
}
