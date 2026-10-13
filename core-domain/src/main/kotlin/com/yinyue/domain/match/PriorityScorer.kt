package com.yinyue.domain.match

/**
 * 音源优先级算法（重点算法之一）。综合两处需求：
 *
 * v1.0 §5.2 打分：priorityScore = 音质(FLAC24BIT/FLAC=4、320K=3、128K=2、未知=1) × 10
 *                              + 原唱(原唱=1/翻唱=0/未知=-1) × 2
 *                              + 来源权重（音源1 平台=1，MV/兜底音轨=0）
 * v1.0 规则3 四级序：原唱高音质 > 原唱低音质 > 翻唱高音质 > 翻唱低音质
 * v3.0 §3.3 实体内链接排序：音质 > 脚本优先级 > 响应时间
 *
 * 因此链接排序键 = (priorityScore DESC, scriptPriority DESC, responseTimeMs ASC, id ASC)
 */
object PriorityScorer {

    /** 原唱标记（对齐 v2.0 TrackSource.originTag 语义） */
    const val ORIGINAL = 1
    const val COVER = 0
    const val UNKNOWN_ORIGIN = -1

    /**
     * 来源权重：音源1（已注册的音频平台 = 支持搜索/元数据）为 1，其余（MV 音轨/本地/抖音）为 0。
     * 通过注册表判定而非硬编码平台名——**新增平台自动按音源1 参与排序**，无需改算法。
     */
    fun audioSourceWeight(platform: String, isRegistered: (String) -> Boolean): Int =
        if (isRegistered(platform)) 1 else 0

    /** 内置平台兜底判定（未接入注册表时的默认实现，单测/离线场景用） */
    fun sourceWeight(platform: String): Int = when (platform) {
        "kw", "wy", "kg", "tx", "mg" -> 1
        else -> 0
    }

    /**
     * 源分档（用户规则：**手动 > 自动**）——决定起播/播放时先试哪一条：
     *   TIER_MANUAL(2) 兜底导入：用户手动指定的文件/链接（platform=local、抖音解析）；
     *   TIER_LOCAL(1)  本地缓存/下载：已落盘，省流量、可离线；
     *   TIER_AUTO(0)   在线自动：脚本取链 / B站自动匹配。
     * 同档内继续沿用 [score] 的排序键（调用方用稳定排序）。
     */
    const val TIER_MANUAL = 2
    const val TIER_LOCAL = 1
    const val TIER_AUTO = 0

    fun sourceTier(isManualImport: Boolean, hasLocalFile: Boolean): Int = when {
        isManualImport -> TIER_MANUAL
        hasLocalFile -> TIER_LOCAL
        else -> TIER_AUTO
    }

    /**
     * 打分（**以原唱为主**，用户裁决 + v1.0 规则3 四级序）：
     * `原唱×100 + 音质×10 + 来源权重`。
     *
     * 为什么改权重：文档两处规则原本冲突——v1.0 要求"原唱的高音质"优先（四级序：
     * 原唱高音质 > 原唱低音质 > 翻唱高音质 > 翻唱低音质），v3.0 §3.3 写的是"音质 > 脚本优先级 > 响应时间"。
     * 而旧公式 `音质×10 + 原唱×2` 实际让**音质压过原唱**（翻唱无损 41 > 原唱320K 33），两条都不满足。
     * 按用户"以原唱为主"的裁决，原唱升为主键（×100），音质次之（×10），来源权重再次。
     * 例：原唱无损 = 100+40+1 = 141；原唱320K = 131；翻唱无损 = 41 → 原唱恒在翻唱之前，与音质无关。
     * 同分时继续用「脚本优先级 → 响应时间 → id」在 SQL 侧兜底排序。
     */
    fun score(qualityLevel: Int, isOriginal: Int, platform: String): Int =
        isOriginal * 100 + qualityLevel * 10 + sourceWeight(platform)

    /** 打分（显式权重版，新增平台经注册表判定时用） */
    fun score(qualityLevel: Int, isOriginal: Int, sourceWeight: Int): Int =
        isOriginal * 100 + qualityLevel * 10 + sourceWeight

    /** 排序比较器键：(score, scriptPriority, responseTimeMs) 降/降/升 */
    fun <T> comparator(
        qualityOf: (T) -> Int,
        originalOf: (T) -> Int,
        platformOf: (T) -> String,
        scriptPriorityOf: (T) -> Int,
        responseTimeOf: (T) -> Long?,
    ): Comparator<T> = Comparator { a, b ->
        val sa = score(qualityOf(a), originalOf(a), platformOf(a))
        val sb = score(qualityOf(b), originalOf(b), platformOf(b))
        when {
            sa != sb -> sb - sa
            else -> {
                val pa = scriptPriorityOf(a)
                val pb = scriptPriorityOf(b)
                when {
                    pa != pb -> pb - pa
                    else -> {
                        val ra = responseTimeOf(a) ?: Long.MAX_VALUE
                        val rb = responseTimeOf(b) ?: Long.MAX_VALUE
                        ra.compareTo(rb)
                    }
                }
            }
        }
    }

    /** 四级优先级标签（供 UI 展示/调试）：ORIGINAL_HQ/ORIGINAL_LQ/COVER_HQ/COVER_LQ */
    fun tier(isOriginal: Int, qualityLevel: Int): String = when {
        isOriginal == ORIGINAL && qualityLevel >= 3 -> "原唱高音质"
        isOriginal == ORIGINAL -> "原唱低音质"
        qualityLevel >= 3 -> "翻唱高音质"
        else -> "翻唱低音质"
    }
}
