package com.yinyue.player.mv.bilibili

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 WBI 签名（对应 v3.0 §5.2，约 60 行 Kotlin）。
 * 算法与混淆表来自 bilibili-API-collect 社区维护文档；
 * 密钥变化时只需更新 MIXIN_KEY_ENC_TAB（风险 R3 的对策）。
 */
object WbiSigner {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52,
    )

    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val REFERER: String = "https://www.bilibili.com/"

    /** imgKey + subKey 按混淆表重排，取前 32 位 */
    fun mixinKey(imgKey: String, subKey: String): String {
        val combined = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in MIXIN_KEY_ENC_TAB) {
            if (i < combined.length) sb.append(combined[i])
        }
        return sb.toString().take(32)
    }

    /** 参数排序 + 过滤 value 中 !'()* + 拼接 wts，产出待签名 query（不含 w_rid） */
    fun buildQuery(params: Map<String, String>, wts: Long): String {
        val withWts = LinkedHashMap<String, String>(params.size + 1)
        withWts.putAll(params)
        withWts["wts"] = wts.toString()
        return withWts.entries
            .map { it.key to it.value.filterNot { c -> c in "!'()*" } }
            .sortedBy { it.first }
            .joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
    }

    /** 追加 wts 并计算 w_rid，返回带 wts/w_rid 的完整参数 */
    fun sign(params: Map<String, String>, imgKey: String, subKey: String, wts: Long): Map<String, String> {
        val query = buildQuery(params, wts)
        val wRid = md5Hex(query + mixinKey(imgKey, subKey))
        val out = LinkedHashMap<String, String>(params.size + 2)
        out.putAll(params)
        out["wts"] = wts.toString()
        out["w_rid"] = wRid
        return out
    }

    /** encodeURIComponent 语义：URLEncoder 修正 + → %20、~ → ~ */
    fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
