package com.yinyue.domain.normalize

/**
 * 显示名清洗（用户要求：歌名要干净，"晴天"就是"晴天"，后面不加任何乱七八糟的东西；
 * 凡是加了后缀/修饰的一律拿去重校验，判定重复即合并）。
 *
 * 与 normalizeSong 的分工：
 * - normalizeSong：归一为去重键（小写、去符号、最激进）
 * - coreSongName / cleanMvTitle：**保留可读性**的展示名（大小写保留、只去修饰）
 */
object NameCleaner {

    /** 音频歌名的修饰后缀/拼接（命中即截断或移除） */
    private val PLUS_SPLIT = Regex("""[+＋]""")
    // feat/ft 后可无空格（"ft.阿信"）；with 要求空格避免误伤正常词
    private val FEAT_RX = Regex("""(?i)\s*[\(\（\[【]?\s*(?:feat|ft)\.?\s*[^)\）\]】]*[\)\）\]】]?|\s+with\s+[^)\）\]】]*[\)\）\]】]?""")

    /** 尾部曲目修饰词（仅尾部匹配，避免误伤歌名本身） */
    private val DECORATION_TAIL_RX = Regex(
        """(?i)[\s·\-]*[\(\（\[【]?\s*(插曲|主题曲|片尾曲|片头曲|宣传曲|推广曲|原声带|配乐|ost|op|ed)\s*[\)\）\]】]?\s*$""",
    )

    /**
     * 音频：提取核心展示歌名。
     * "晴天+告白气球 (Live)" → "晴天"
     * "晴天 - Live版"       → "晴天"
     * "晴天（周杰伦）"        → "晴天"
     * "晴天 feat. 某某"      → "晴天"
     */
    fun coreSongName(raw: String): String {
        var s = raw.trim()
        // 1. 去掉 " + " 拼接（串烧/合唱）：取主歌名
        s = PLUS_SPLIT.split(s).firstOrNull()?.trim() ?: s
        // 2. 去 feat./ft./with 段
        s = FEAT_RX.replace(s, "").trim()
        // 3. 去括号及内容（中英文圆/方/花/书名号等，含嵌套）
        s = stripBrackets(s)
        // 4. 去 " - 后缀"（横杠及其后）
        val dashIdx = s.indexOfFirst { it == '-' || it == '－' || it == '—' || it == '–' }
        if (dashIdx > 0) s = s.substring(0, dashIdx).trim()
        // 4.5 去尾部曲目修饰词（"晴天插曲"/"晴天 主题曲"/"晴天OST" → "晴天"）
        s = DECORATION_TAIL_RX.replace(s, "").trim()
        // 5. 去尾部悬空分隔符与多余空白
        s = s.trim().trimEnd('、', ',', '，', '.', '。', '_', '-', '－').trim()
        // 空结果则回退原始（避免清成空串）
        return s.ifBlank { raw.trim() }
    }

    /** 重复判定用的核心键（在 coreSongName 基础上再做全角/大小写/符号归一） */
    fun coreKey(raw: String): String =
        com.yinyue.domain.match.SongMatcher.filterStr(coreSongName(raw)).lowercase()

    /** 多歌手分隔符（与 SongMatcher 一致） */
    private val ARTIST_SPLIT_RX = Regex("[、&;；/,，|]")

    /**
     * 判断某条目的歌手是否为搜索词（支持多歌手字段、别名表与包含关系）。
     * 用于"搜歌手 → 展示该歌手全部歌曲"的识别。
     */
    fun artistMatches(
        artistName: String,
        keywordKey: String,
        alias: AliasTable = AliasTable.DEFAULT,
    ): Boolean {
        if (keywordKey.isBlank() || artistName.isBlank()) return false
        fun canonical(k: String): String = alias.canonicalOfLoose(k)?.let { coreKey(it) } ?: k
        val parts = ARTIST_SPLIT_RX.split(artistName)
            .map { canonical(coreKey(it)) }
            .filter { it.isNotBlank() }
        val kw = canonical(coreKey(keywordKey))
        return parts.any { it == kw || it.contains(kw) || kw.contains(it) }
    }

    /**
     * 歌名是否为歌手名（脏数据：部分平台的搜索结果会把歌手名当歌名）。
     * 该类条目在歌手搜索时应被过滤掉（用户要求"歌曲名不能是歌手名"）。
     */
    fun songNameEqualsArtist(songName: String, artistName: String): Boolean {
        val s = coreKey(songName)
        if (s.isBlank()) return false
        val parts = ARTIST_SPLIT_RX.split(artistName).map { coreKey(it) }.filter { it.isNotBlank() }
        return s in parts
    }

    /**
     * MV 标题清洗（B站/抖音）：
     * "【4K】蕾哈娜 x JAY-Z 经典神曲《Umbrella》MV 2007 超分画质增强版" → "Umbrella"
     * "周杰伦 晴天 官方MV 1080P" → "周杰伦 晴天"
     * "[Wayward]9/15号录播 qingtian代播" → "[Wayward]9/15号录播 qingtian代播"（无法提取则原样，避免清空）
     */
    fun cleanMvTitle(raw: String): String {
        val s0 = raw.trim()
        // 1. 书名号内容优先（最可靠的歌名信号）：如"…神曲《Umbrella》MV 2007…" → Umbrella
        val inBookQuote = Regex("""《([^》]{1,40})》""").find(s0)?.groupValues?.get(1)?.trim()
        if (!inBookQuote.isNullOrBlank()) return inBookQuote
        // 2. 无书名号：去【】/[] 标签后清噪声词
        var s = Regex("""[【\[][^】\]]{0,30}[】\]]""").replace(s0, "")
        val noise = listOf(
            "4K", "8K", "1080P", "720P", "480P", "HDR", "高清", "超清", "超分", "画质增强版", "画质增强",
            "修复版", "修复", "完整版", "官方版", "官方", "中文字幕", "中英字幕", "字幕组", "字幕", "中英",
            "MV", "mv", "M/V", "PV", "现场版", "live版", "Live版", "无损音质", "音质", "耳机福利", "循环",
        )
        noise.forEach { s = s.replace(it, " ") }
        s = s.replace(Regex("""\s+"""), " ").trim().trim('-', '|', '：', ':', '·', '—').trim()
        return s.ifBlank { raw.trim() }
    }

    /** 剥离括号及内容（含嵌套；与 MetadataNormalizer 同规则，此处独立实现避免耦合） */
    private fun stripBrackets(s: String): String {
        val pairs = mapOf(
            '（' to '）', '(' to ')', '【' to '】', '[' to ']', '〔' to '〕',
            '｛' to '｝', '{' to '}', '「' to '」', '『' to '』', '《' to '》', '〈' to '〉',
        )
        val closes = pairs.values.toSet()
        if (s.none { it in pairs.keys || it in closes }) return s
        val sb = StringBuilder()
        var depth = 0
        var inBracket = false
        for (c in s) {
            if (c in pairs.keys) {
                depth++
                inBracket = true
                continue
            }
            if (c in closes) {
                if (depth > 0) depth--
                if (depth == 0) inBracket = false
                continue
            }
            if (!inBracket) sb.append(c)
        }
        return sb.toString()
    }
}
