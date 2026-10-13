package com.yinyue.domain.normalize

/**
 * 歌曲版本词表（8 类），逐类移植自 lx-music-desktop musicSdk/versionChars.ts。
 * 用途：匹配一致性检查（原歌含某类版本词时，候选也应含/不含同类词）与原唱验证降权。
 */
object VersionKeywords {
    /** 类别下标：0 伴奏/无人声 1 现场 2 翻唱 3 混音 4 原声/不插电 5 Demo 6 速度变化 7 特殊乐器版 */
    const val CAT_INSTRUMENTAL = 0
    const val CAT_LIVE = 1
    const val CAT_COVER = 2
    const val CAT_REMIX = 3
    const val CAT_ACOUSTIC = 4
    const val CAT_DEMO = 5
    const val CAT_SPEED = 6
    const val CAT_SPECIAL_INSTRUMENT = 7

    val VERSION_CHARS: List<List<String>> = listOf(
        // 伴奏 / 无人声
        listOf(
            "instrumental", "inst", "karaoke", "backing track", "minus one", "minus vocal",
            "no vocal", "off vocal", "vocal removed", "伴奏", "伴唱", "卡拉ok", "纯音乐",
            "无人声", "去人声", "消音", "オフボーカル", "インスト", "반주", "노래방",
        ),
        // 现场
        listOf(
            "live", "concert", "tour", "festival", "stage", "session",
            "现场", "演唱会", "巡演", "音乐会", "ライブ", "공연", "라이브",
        ),
        // 翻唱
        listOf("cover", "tribute", "rendition", "remake", "翻唱", "致敬", "改编", "カバー", "커버"),
        // 混音
        listOf(
            "remix", "mix", "club", "dance", "extended", "house", "edm", "dj", "dub", "trap",
            "混音", "重混", "电音", "リミックス", "リ믹스",
        ),
        // 原声 / 不插电
        listOf("acoustic", "unplugged", "stripped", "原声", "不插电", "アコースティック", "어쿠스틱"),
        // Demo
        listOf("demo", "小样", "试听", "デモ", "데모"),
        // 速度变化
        listOf("slowed", "slowed reverb", "sped up", "speed up", "nightcore", "慢速", "加速", "变速", "ナイトコア"),
        // 特殊乐器版
        listOf(
            "piano", "guitar", "orchestra", "symphony", "violin",
            "钢琴", "吉他", "管弦乐", "小提琴", "ピアノ", "ギター", "피아노", "기타",
        ),
    )

    /** 扁平化的全部版本词（对齐落雪 versionChars 的逐个字符判定用法） */
    val ALL_CHARS: List<String> = VERSION_CHARS.flatten()

    /** 返回文本命中的版本类别集合（小写包含匹配） */
    fun matchedCategories(text: String): Set<Int> {
        val lower = text.lowercase()
        val hits = mutableSetOf<Int>()
        VERSION_CHARS.forEachIndexed { index, words ->
            if (words.any { lower.contains(it) }) hits.add(index)
        }
        return hits
    }
}
