package com.yinyue.domain.normalize

/** 元数据归一层（v3.0 第四章，双源并列的分水岭） */
object MetadataNormalizer {

    /** 去重模式（v1.0 规则4，设置页可切换） */
    enum class DedupMode { SONG_ONLY, SONG_AND_ARTIST }

    // 需要剥除的括号对（含内容），覆盖中英文圆/方/花括号及书名号等
    private val BRACKET_OPEN = mapOf(
        '（' to '）', '(' to ')', '【' to '】', '[' to ']', '〔' to '〕',
        '｛' to '｝', '{' to '}', '「' to '」', '『' to '』', '《' to '》', '〈' to '〉',
    )
    private val BRACKET_CLOSE = BRACKET_OPEN.values.toSet()

    // 特殊符号（对齐 lx filterStr 的字符集），歌名归一时移除
    private val SYMBOL_RX = Regex(
        "[`~!@#$%^&*_\\-+=<>?:\"|,./;'\\\\·～！@#￥%……&*——+＝|《》？：\"\"【】；''，。、？]",
    )
    private val WHITESPACE_RX = Regex("\\s+")

    /** 多艺人分隔符（对齐 lx findMusic 的 sortSingle 思路） */
    private val ARTIST_SPLIT_RX = Regex("[/、,，&×]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE)

    /**
     * 歌名归一（v1.0 规则2 + v3.0 §4.1 合并）：
     * 去括号及内容 → 去横杠及后缀（" - Live版"）→ 去空白 → 繁转简 → 去特殊符号 → 小写
     */
    fun normalizeSong(name: String): String {
        var s = stripBrackets(name)
        s = cutDashSuffix(s)
        s = WHITESPACE_RX.replace(s, "")
        s = runCatching { com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(s) }.getOrDefault(s)
        s = SYMBOL_RX.replace(s, "")
        return s.lowercase()
    }

    /**
     * 歌手归一：拆分多艺人 → 各自繁转简 + 别名映射 → 排序 → "/" 连接。
     * 排序保证 "A/B" 与 "B/A" 同键。
     */
    fun normalizeArtist(artist: String, alias: AliasTable = AliasTable.DEFAULT): String {
        val parts = ARTIST_SPLIT_RX.split(artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { part ->
                var p = runCatching { com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(part) }
                    .getOrDefault(part)
                p = alias.canonicalOf(p.trim().lowercase()) ?: p.trim()
                p.lowercase()
            }
            .distinct()
        return if (parts.isEmpty()) "" else parts.sorted().joinToString("/")
    }

    /** 归一匹配键；模式②为 "歌名::歌手"，模式①仅歌名（忽略歌手） */
    fun normalizedKey(
        songName: String,
        artistName: String,
        mode: DedupMode = DedupMode.SONG_AND_ARTIST,
        alias: AliasTable = AliasTable.DEFAULT,
    ): String {
        val song = normalizeSong(songName)
        return when (mode) {
            DedupMode.SONG_ONLY -> song
            DedupMode.SONG_AND_ARTIST -> song + "::" + normalizeArtist(artistName, alias)
        }
    }

    /** Levenshtein 距离（模糊匹配兜底，阈值 ≤1 视为同实体） */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    /** v1.0 规则：移除横杠及之后的后缀（如" - Live版"）；切空则回退原文 */
    private fun cutDashSuffix(s: String): String {
        val idx = s.indexOf('-')
        if (idx <= 0) return s
        val head = s.substring(0, idx).trim()
        return if (head.isEmpty()) s else head
    }

    /** 剥除括号及括号内内容（含嵌套；不成对括号仅移除括号字符本身） */
    private fun stripBrackets(s: String): String {
        if (s.none { it in BRACKET_OPEN || it in BRACKET_CLOSE }) return s
        val sb = StringBuilder()
        var depth = 0
        var inBracket = false
        for (c in s) {
            if (c in BRACKET_OPEN) {
                depth++
                inBracket = true
                continue
            }
            if (c in BRACKET_CLOSE) {
                if (depth > 0) depth--
                if (depth == 0) inBracket = false
                continue
            }
            if (!inBracket) sb.append(c)
        }
        return sb.toString()
    }
}
