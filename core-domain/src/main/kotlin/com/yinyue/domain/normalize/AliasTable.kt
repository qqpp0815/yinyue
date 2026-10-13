package com.yinyue.domain.normalize

/**
 * 歌手别名表：英文名/艺名 ↔ 中文标准名。
 * 内置常见映射（键为归一后小写），用户可在设置中补充（Task 23 接线）。
 */
class AliasTable(extraAliases: Map<String, String> = emptyMap()) {

    private val aliases: Map<String, String> = buildMap {
        putAll(BUILTIN)
        extraAliases.forEach { (k, v) -> put(k.trim().lowercase(), v.trim()) }
    }

    /** 归一后的别名 → 标准名；未命中返回 null */
    fun canonicalOf(normalizedName: String): String? = aliases[normalizedName]

    /** 宽松查找（键已去空格/符号，如 "jaychou" → "周杰伦"），供核心键匹配用 */
    private val looseAliases: Map<String, String> by lazy {
        aliases.mapKeys { com.yinyue.domain.match.SongMatcher.filterStr(it.key).lowercase() }
    }

    fun canonicalOfLoose(normalizedKey: String): String? = looseAliases[normalizedKey]

    /** 反查：给定标准名（或其部分），返回所有可命中标题的别名/名称候选 */
    fun allNamesFor(canonicalNames: Collection<String>): List<String> {
        val canonicalSet = canonicalNames.map { it.lowercase() }.toSet()
        return (aliases.filterValues { it.lowercase() in canonicalSet }.keys + canonicalSet).toList()
    }

    companion object {
        // 注意：BUILTIN 必须声明在 DEFAULT 之前（伴生对象按声明顺序初始化）
        private val BUILTIN: Map<String, String> = mapOf(
            "jay chou" to "周杰伦",
            "周董" to "周杰伦",
            "eason chan" to "陈奕迅",
            "g.e.m." to "邓紫棋",
            "gem" to "邓紫棋",
            "gloria tang" to "邓紫棋",
            "jj lin" to "林俊杰",
            "mayday" to "五月天",
            "stefanie sun" to "孙燕姿",
            "jolin tsai" to "蔡依林",
            "leehom wang" to "王力宏",
            "wang leehom" to "王力宏",
            "faye wong" to "王菲",
            "jane zhang" to "张靓颖",
            "hua chenyu" to "华晨宇",
            "zhou shen" to "周深",
            "charlie zhou" to "周深",
            "mao buyi" to "毛不易",
            "tfboys" to "tfboys",
            "soda green" to "苏打绿",
            "rainie yang" to "杨丞琳",
            "show luo" to "罗志祥",
            "hebe tien" to "田馥甄",
            "a-mei" to "张惠妹",
            "a-mei chang" to "张惠妹",
            "sherry chang" to "张惠妹",
            "eric chou" to "周兴哲",
            "kuang tian" to "汪苏泷",
            "silence wang" to "汪苏泷",
            "deng ziqi" to "邓紫棋",
            "li ronghao" to "李荣浩",
            "xue zhiqian" to "薛之谦",
            "joker xue" to "薛之谦",
            "alan walker" to "alan walker",
            "taylor swift" to "taylor swift",
        )

        val DEFAULT = AliasTable()
    }
}
