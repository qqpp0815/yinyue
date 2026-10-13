package com.yinyue.domain.match

/**
 * 实体内链接的合并限量（用户要求："限制合并的数量，以原唱为主"）。
 *
 * 背景：默认去重模式是"仅歌名"，同名歌曲（含大量他人翻唱）会并进同一实体——
 * 实测《勇气》一个实体挂了 117 条链接，其中多数是别人的翻唱。后果有两个：
 *   ① 失败重试逐条尝试上百链接 → 切源/起播像卡死；
 *   ② 兜底会播到别人唱的版本 → "点的是梁静茹，出来是别人"（歌名与声音不符）。
 *
 * 规则：
 *   1. **原唱的链接全保留**（同歌手的不同音质/版本都是同一首歌，合法且有用）；
 *   2. **他人翻唱限量保留**（默认 8 条，按优先级分从高到低）——留一点做兜底，但绝不抢占原唱；
 *   3. 总量再设一个上限（默认 60），防止单实体无限膨胀。
 */
object LinkCaps {

    /** 单个实体最多保留的链接总数 */
    const val MAX_LINKS_PER_ENTITY = 60

    /** 单个实体最多保留的"他人翻唱"链接数 */
    const val MAX_COVER_LINKS = 8

    /** 一条链接的裁剪视图（与 Room 解耦，便于单测） */
    data class Item(
        val id: Long,
        val artistKey: String,
        val priorityScore: Int,
    )

    /**
     * 返回应当**保留**的链接 id 集合。
     * artistKey 不一致 = 他人翻唱（空值视为未知，按原唱处理，避免误删）。
     */
    fun keep(
        items: List<Item>,
        originalArtistKey: String,
        maxTotal: Int = MAX_LINKS_PER_ENTITY,
        maxCovers: Int = MAX_COVER_LINKS,
    ): Set<Long> {
        val original = items.filter { isOriginalArtist(it, originalArtistKey) }
        val covers = items.filter { !isOriginalArtist(it, originalArtistKey) }
        // 翻唱按优先级分降序，只留前 maxCovers 条
        val keptCovers = covers.sortedByDescending { it.priorityScore }.take(maxCovers)
        val kept = original + keptCovers
        // 总量兜底：超限时优先砍翻唱（再砍优先级分最低的）
        if (kept.size <= maxTotal) return kept.map { it.id }.toSet()
        val originalIds = original.map { it.id }.toSet()
        return kept.sortedWith(
            compareByDescending<Item> { it.id in originalIds } // 原唱优先留
                .thenByDescending { it.priorityScore },
        ).take(maxTotal).map { it.id }.toSet()
    }

    private fun isOriginalArtist(item: Item, originalArtistKey: String): Boolean {
        if (item.artistKey.isBlank()) return true // 未知不误删
        if (originalArtistKey.isBlank()) return true // 没有原唱基准时全部保留
        return item.artistKey == originalArtistKey
    }
}
