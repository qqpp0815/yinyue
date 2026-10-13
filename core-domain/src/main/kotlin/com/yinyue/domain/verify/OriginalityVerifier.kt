package com.yinyue.domain.verify

/**
 * 原唱验证双源信号融合（Task 24，v3.0 §4.2）。
 * 信号源A（音源1 平台标签，尽力而为）+ 信号源B（B站匹配链）→ 0~1 融合分；
 * ≥0.70 原唱 / 0.4~0.7 待定 / <0.40 疑似翻唱；用户人工确认权重最高（锁定）。
 */
object OriginalityVerifier {

    enum class State { ORIGINAL, PENDING, SUSPECT_COVER, USER_CONFIRMED }

    data class SignalA(val platformTagOriginal: Boolean? = null) // 平台官方原唱标记（缺失=中性）
    data class SignalB(
        val matched: Boolean = false,          // B站匹配链有结果
        val official: Boolean = false,         // 官方认证UP/标题含官方MV
        val durationMatch: Boolean = false,    // 时长吻合 [-5s,+40s]
        val coverConflict: Boolean = false,    // B站带翻唱词且与歌手冲突
    )

    data class Result(val state: State, val score: Float)

    fun fuse(a: SignalA, b: SignalB): Result {
        var score = 0.5f
        // 信号源A
        when (a.platformTagOriginal) {
            true -> score += 0.35f
            false -> score -= 0.30f
            null -> {}
        }
        // 信号源B
        if (b.matched) {
            if (b.official) score += 0.40f
            if (b.durationMatch) score += 0.10f
            if (b.coverConflict) score -= 0.30f
        }
        val s = score.coerceIn(0f, 1f)
        return Result(toState(s), s)
    }

    fun toState(score: Float): State = when {
        score >= 0.70f -> State.ORIGINAL
        score >= 0.40f -> State.PENDING
        else -> State.SUSPECT_COVER
    }
}
