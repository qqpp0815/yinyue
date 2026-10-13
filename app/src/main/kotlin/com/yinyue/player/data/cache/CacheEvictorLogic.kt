package com.yinyue.player.data.cache

/**
 * 缓存淘汰决策（纯函数，供 ProtectedLruEvictor 调用与单测）。
 * 规则（v3.0 §8 / Task 11/19）：LRU + 收藏白名单保护 + 容量配额。
 */
object CacheEvictorLogic {

    data class SpanInfo(val key: String, val lengthBytes: Long, val lastTouchOrder: Long)

    /**
     * 返回应淘汰的 key 列表（从最旧开始），使总字节 ≤ quota；
     * protectedKeys 中的 key 永不淘汰。
     */
    fun planEvictions(
        spans: List<SpanInfo>, // 已按 lastTouchOrder 升序（最旧在前）
        protectedKeys: Set<String>,
        quotaBytes: Long,
    ): List<String> {
        val total = spans.sumOf { it.lengthBytes }
        if (total <= quotaBytes) return emptyList()
        var running = total
        val out = ArrayList<String>()
        for (span in spans) { // 最旧在前
            if (running <= quotaBytes) break
            if (span.key in protectedKeys) continue
            running -= span.lengthBytes
            out.add(span.key)
        }
        return out
    }
}
