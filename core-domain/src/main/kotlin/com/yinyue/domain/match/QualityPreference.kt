package com.yinyue.domain.match

/**
 * 优先播放音质（对齐落雪 `app_setting.player.playQuality` + `getPlayQuality`）：
 * 用户选一档，取链时**从该档往下找**，找到平台与脚本都支持的最高档；找不到就退到 128k。
 *
 * 落雪规则（照搬）：`TRY_QUALITYS_LIST = ['flac24bit','flac','320k']` 从其首选档位开始向下过滤；
 * **不会向上越档**——选 320k 永远不会给你 flac；选 128k 就固定要 128k。
 */
object QualityPreference {

    /** 可选档位（落雪 UI 也是这四档） */
    const val FLAC24BIT = "flac24bit"
    const val FLAC = "flac"
    const val K320 = "320k"
    const val K128 = "128k"

    /**
     * 「自动」档位：**不设上限**，从最高档往下找（有损源就拉无损，没有才退到 320K/128K）。
     * 与上面四档的"上限语义"相反——那四档表示"不许比这个更好"，这一档表示"能多好就多好"。
     */
    const val AUTO = "auto"

    /** 默认档位：与落雪一致（128k）。注意这是"上限"语义，想要更高音质请选 320K/无损/自动 */
    const val DEFAULT = K128

    /** 四个具体档位（高→低），chain 的下标计算依赖这个顺序 */
    val ALL = listOf(FLAC24BIT, FLAC, K320, K128)

    /** 设置页可选档位：自动 + 四档 */
    val CHOICES = listOf(AUTO, FLAC24BIT, FLAC, K320, K128)

    /** UI 显示名（对齐落雪的四档标签） */
    fun label(quality: String): String = when (quality) {
        AUTO -> "自动（尽可能高）"
        FLAC24BIT -> "无损 24bit"
        FLAC -> "无损"
        K320 -> "320K"
        else -> "128K"
    }

    /** 音质档位对应的优先级分（与 PriorityScorer 的 level 语义一致） */
    fun levelOf(quality: String): Int = when (quality) {
        FLAC24BIT, FLAC -> 4
        K320 -> 3
        else -> 2
    }

    /**
     * 取链尝试序列：首选档位 → 逐级向下 → 128k 兜底。
     * 例：flac24bit → [flac24bit, flac, 320k, 128k]；320k → [320k, 128k]；128k → [128k]。
     */
    fun chain(preferred: String): List<String> {
        // 自动：四档全试（无损 24bit → 无损 → 320K → 128K）
        if (preferred == AUTO) return ALL
        val idx = ALL.indexOf(preferred).let { if (it < 0) ALL.indexOf(DEFAULT) else it }
        return ALL.subList(idx, ALL.size)
    }
}
