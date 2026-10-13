package com.yinyue.domain.match

/**
 * 原唱判定（v3.0 §4.2 信号源A 的落地实现）：
 * 按"歌名 - 演唱者"多源交叉——同一首歌（去重同组）内，归一歌手出现最多者即原唱演唱者；
 * 与其不同的演唱者即翻唱/他人版本。多平台一致（≥2 个平台）才判原唱，单条不轻易下结论。
 *
 * 票数按**平台**去重计数：同一平台返回同一歌手的多个版本/多个发布（酷我常一次返回 5 条
 * 同名发行）只算一票，否则单平台的版本堆积就能凑够 2 票、把翻唱歌手判成原唱。
 *
 * 逻辑为纯函数，便于单测与后续接入 B站信号（信号源B）融合。
 */
object OriginalityVoter {

    /** 判定原唱所需的最少平台票数（多平台一致才算数） */
    const val MIN_VOTES_FOR_ORIGINAL = 2

    /** 一条投票来源：归一歌手 + 平台 */
    data class Vote(val artistKey: String, val platform: String)

    /** 统计归一歌手的**平台**票数 */
    fun tally(votes: List<Vote>): Map<String, Int> =
        votes.filter { it.artistKey.isNotBlank() }
            .groupBy { it.artistKey }
            .mapValues { (_, list) -> list.map { it.platform }.distinct().size }

    /**
     * 单条链接的原唱标记：
     * - 主流演唱者且平台票数 ≥2 → ORIGINAL(1)
     * - 非主流演唱者 → COVER(0)（同组内他人版本）
     * - 其余（票数不足/歌手缺失）→ UNKNOWN(-1)
     */
    fun tagOf(artistKey: String, votes: Map<String, Int>): Int {
        if (artistKey.isBlank() || votes.isEmpty()) return PriorityScorer.UNKNOWN_ORIGIN
        val mainstream = votes.maxByOrNull { it.value }?.key ?: return PriorityScorer.UNKNOWN_ORIGIN
        val mainstreamCount = votes[mainstream] ?: 0
        return when {
            artistKey == mainstream && mainstreamCount >= MIN_VOTES_FOR_ORIGINAL -> PriorityScorer.ORIGINAL
            artistKey != mainstream -> PriorityScorer.COVER
            else -> PriorityScorer.UNKNOWN_ORIGIN
        }
    }

    /** 该组是否存在可信原唱（用于回写实体级 verifiedOriginal） */
    fun hasTrustedOriginal(votes: Map<String, Int>): Boolean =
        (votes.maxByOrNull { it.value }?.value ?: 0) >= MIN_VOTES_FOR_ORIGINAL
}
