package com.yinyue.player.domain.play

import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.domain.match.PriorityScorer

/**
 * 链接尝试顺序（起播 / 切源 / 音源列表三处共用同一口径，避免"列表顺序 ≠ 实际尝试顺序"）：
 *
 *  1. **外置脚本优先级**（用户可调，上移/下移）：平台由高优先级脚本提供者优先——
 *     用户把哪个源排前面，就用哪个源；
 *  2. 源分档：手动导入 > 本地缓存 > 在线自动（见 PriorityScorer.sourceTier）；
 *  3. 链接优先级分：音质×10 + 原唱×2 + 来源权重；
 *  4. 脚本优先级、响应时间、id（与 DAO 的稳定序一致）。
 */
object SourceOrdering {

    fun ordered(
        links: List<AudioLink>,
        platformPriority: Map<String, Int>,
        localFileExists: (AudioLink) -> Boolean,
    ): List<AudioLink> = links.sortedWith(
        compareByDescending<AudioLink> { platformPriority[it.platform] ?: Int.MIN_VALUE }
            .thenByDescending { PriorityScorer.sourceTier(isManual(it), localFileExists(it)) }
            .thenByDescending { it.priorityScore }
            .thenByDescending { it.scriptPriority }
            .thenBy { it.responseTimeMs ?: Long.MAX_VALUE }
            .thenBy { it.id },
    )

    /** 手动导入（兜底）链接：platform=local（用户手动指定的文件/链接） */
    fun isManual(link: AudioLink): Boolean = link.platform == "local"
}
