package com.yinyue.domain.lyric

/**
 * LRC 歌词解析器（Task 9，纯函数）。
 * 支持：多时间标签行、offset 标签、异常行跳过、逐字增强标记剥离。
 */
object LrcParser {

    data class LyricLine(val timeMs: Long, val text: String)

    data class LrcData(
        val lines: List<LyricLine>, // 已按时间升序
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val offsetMs: Long = 0,
    )

    private val TIME_TAG_RX: Regex by lazy {
        Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    }
    private val META_RX: Regex by lazy {
        Regex("""\[(ti|ar|al|offset|by|ver):(.*)]""")
    }
    private val WORD_TIME_RX: Regex by lazy {
        Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    }

    fun parse(text: String): LrcData {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0L
        val lines = ArrayList<LyricLine>(128)

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            META_RX.find(line)?.let { m ->
                when (m.groupValues[1]) {
                    "ti" -> title = m.groupValues[2].trim()
                    "ar" -> artist = m.groupValues[2].trim()
                    "al" -> album = m.groupValues[2].trim()
                    "offset" -> offsetMs = m.groupValues[2].trim().toLongOrNull() ?: 0L
                }
            }
            // 单遍扫描：收集时间标签同时拼出正文，避免二次正则替换
            val times = TIME_TAG_RX.findAll(line)
            val sb = StringBuilder(line.length)
            var last = 0
            var count = 0
            val tagList = ArrayList<Long>(2)
            for (t in times) {
                tagList.add(parseTagToMs(t))
                sb.append(line, last, t.range.first)
                last = t.range.last + 1
                count++
            }
            if (count == 0) continue
            sb.append(line, last, line.length)
            var content = sb.toString()
            if (content.indexOf('<') >= 0) content = WORD_TIME_RX.replace(content, "")
            content = content.trim()
            for (t in tagList) lines.add(LyricLine(t, content))
        }
        val sorted = lines.sortedBy { it.timeMs }
        return LrcData(sorted, title, artist, album, offsetMs)
    }

    private fun parseTagToMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val fracStr = m.groupValues[3]
        val fracMs = when (fracStr.length) {
            0 -> 0L
            1 -> fracStr.toLong() * 100
            2 -> fracStr.toLong() * 10
            else -> fracStr.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + fracMs
    }

    /** 二分查找当前播放时间对应的行下标（-1 = 尚未开始）；offset 由调用方先行加到 position 上 */
    fun findCurrentLine(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
