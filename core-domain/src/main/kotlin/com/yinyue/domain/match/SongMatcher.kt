package com.yinyue.domain.match

import com.yinyue.domain.normalize.VersionKeywords

/**
 * 跨源歌曲匹配算法（去重核心）——逐行移植自 lx-music-desktop musicSdk/index.js 的 findMusic。
 *
 * 判定要素（与落雪一致）：
 * 1. filterStr：移除空白与特殊符号（字符集与落雪相同）
 * 2. sortSingle：多歌手拆分 + 排序归一（分隔符 /、|&|;|；|,|，|\|）
 * 3. 时长 ±5 秒容差
 * 4. 版本词一致性（versionChars：两边都有或都没有，才能视为同一版本）
 * 5. 歌名/歌手双向包含
 *
 * 另含 9 级优先排序（sortMusic 等价实现），用于换源时挑最匹配的目标。
 */
object SongMatcher {

    /** 歌手分隔符（对齐落雪 singersRxp） */
    private val SINGERS_RXP = Regex("[、&;；/,，|]")

    /** filterStr 字符集（对齐落雪：\s ' . , ， & " 、 ( ) （ ） ` ~ - < > | / ] [ ! ！） */
    private val FILTER_RX = Regex("""[\s'.,，&"、()（）`~\-<>|/\]\[!！]""")

    /** 时长字符串（"MM:SS" / "HH:MM:SS"）→ 秒 */
    fun parseIntv(interval: String?): Int {
        if (interval.isNullOrBlank()) return 0
        var intv = 0
        var unit = 1
        val arr = interval.split(":")
        for (i in arr.indices.reversed()) {
            intv += (arr[i].toIntOrNull() ?: 0) * unit
            unit *= 60
        }
        return intv
    }

    /** filterStr：去空白与特殊符号（落雪一致） */
    fun filterStr(s: String?): String = FILTER_RX.replace(s ?: "", "")

    /** sortSingle：多歌手拆分后排序、顿号连接（落雪用 localeCompare，此处用自然序） */
    fun sortSingle(singer: String?): String {
        val s = singer ?: ""
        return if (SINGERS_RXP.containsMatchIn(s)) {
            s.split(SINGERS_RXP).sortedWith(compareBy({ it })).joinToString("\u3001")
        } else {
            s
        }
    }

    /** 待匹配条目的归一字段（一次算好，多次比对复用） */
    data class Key(
        val name: String,
        val fMusicName: String,
        val fSinger: String,
        val fAlbumName: String,
        val fInterval: Int,
    ) {
        companion object {
            fun of(name: String, singer: String, albumName: String?, intervalSec: Int): Key = Key(
                name = name,
                fMusicName = filterStr(name).lowercase(),
                fSinger = filterStr(sortSingle(singer)).lowercase(),
                fAlbumName = filterStr(albumName).lowercase(),
                fInterval = intervalSec,
            )
        }
    }

    /** 时长容差 ±5s（落雪 isEqualsInterval） */
    fun isEqualsInterval(a: Int, b: Int): Boolean = Math.abs(a - b) <= 5

    /** 版本词一致性（落雪 isEqualsVersionMusicNameChar） */
    fun isEqualsVersionName(name: String, fMusicName: String): Boolean {
        for (char in VersionKeywords.ALL_CHARS) {
            if (name.contains(char) != fMusicName.contains(char)) return false
        }
        return true
    }

    /** 歌名双向包含 + 版本词一致（落雪 isIncludesName） */
    fun isIncludesName(name: String, fMusicName: String): Boolean =
        (fMusicName.contains(name) || name.contains(fMusicName)) && isEqualsVersionName(name, fMusicName)

    /** 歌手双向包含（落雪 isIncludesSinger；目标歌手为空视为匹配） */
    fun isIncludesSinger(singer: String, fSinger: String): Boolean =
        if (fSinger.isEmpty()) true else fSinger.contains(singer) || singer.contains(fSinger)

    /** 专辑相等（落雪 isEqualsAlbum；目标专辑为空视为匹配） */
    fun isEqualsAlbum(album: String, fAlbumName: String): Boolean =
        if (fAlbumName.isEmpty()) true else fAlbumName == album

    /**
     * 两条候选是否同一首歌（落雪三轮匹配的判定内核）：
     * ① 时长容差 ② 歌名精确 + 歌手包含 ③ 歌手精确 + 歌名包含（含版本词一致）④ 专辑 + 歌手 + 歌名
     */
    fun isSameSong(a: Key, b: Key): Boolean {
        // 时长：两边都有则必须 ±5s 内；一边缺失则跳过此条件（允许合并，避免漏并）
        if (a.fInterval > 0 && b.fInterval > 0 && !isEqualsInterval(a.fInterval, b.fInterval)) return false
        // ① 歌名精确 + 歌手包含
        if (a.fMusicName.isNotEmpty() && a.fMusicName == b.fMusicName && isIncludesSinger(a.fSinger, b.fSinger)) return true
        // ② 歌手精确 + 歌名包含（版本词一致）
        if (a.fSinger.isNotEmpty() && a.fSinger == b.fSinger && isIncludesName(a.fMusicName, b.fMusicName)) return true
        // ③ 专辑 + 歌手 + 歌名
        if (a.fAlbumName.isNotEmpty() && isEqualsAlbum(a.fAlbumName, b.fAlbumName) &&
            isIncludesSinger(a.fSinger, b.fSinger) && isIncludesName(a.fMusicName, b.fMusicName)
        ) {
            return true
        }
        return false
    }

    /**
     * 去重分组（重点算法）：把多平台的候选合并为"同一首歌"的若干组。
     * 逐条尝试并入已有组（与组内任一成员判定同一首歌即并入），否则新建组。
     * 组内顺序保持输入顺序（后续按优先级排序由 PriorityScorer 负责）。
     */
    fun <T> groupSongs(
        items: List<T>,
        keyOf: (T) -> Key,
    ): List<List<T>> {
        val groups = ArrayList<MutableList<T>>()
        val keys = ArrayList<MutableList<Key>>()
        for (item in items) {
            val k = keyOf(item)
            var placed = false
            for (i in groups.indices) {
                if (keys[i].any { isSameSong(k, it) }) {
                    groups[i].add(item)
                    keys[i].add(k)
                    placed = true
                    break
                }
            }
            if (!placed) {
                groups.add(mutableListOf(item))
                keys.add(mutableListOf(k))
            }
        }
        return groups
    }

    /**
     * 9 级优先排序（落雪 sortMusic 序列的等价实现）：
     * 目标(target) 与候选项的匹配强度由强到弱；返回排序后的列表。
     */
    fun <T> rankByCloseness(target: Key, candidates: List<T>, keyOf: (T) -> Key): List<T> {
        val remaining = candidates.toMutableList()
        val out = ArrayList<T>(candidates.size)
        fun take(pred: (Key) -> Boolean) {
            val it = remaining.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (pred(keyOf(item))) {
                    out.add(item)
                    it.remove()
                }
            }
        }
        take { k -> k.fSinger == target.fSinger && k.fMusicName == target.fMusicName && k.fInterval == target.fInterval }
        take { k -> k.fMusicName == target.fMusicName && k.fSinger == target.fSinger && k.fAlbumName == target.fAlbumName }
        take { k -> k.fSinger == target.fSinger && k.fMusicName == target.fMusicName }
        take { k -> k.fMusicName == target.fMusicName && k.fInterval == target.fInterval }
        take { k -> k.fSinger == target.fSinger && k.fInterval == target.fInterval }
        take { k -> k.fInterval == target.fInterval }
        take { k -> k.fMusicName == target.fMusicName }
        take { k -> k.fSinger == target.fSinger }
        take { k -> k.fAlbumName == target.fAlbumName }
        out.addAll(remaining)
        return out
    }
}
