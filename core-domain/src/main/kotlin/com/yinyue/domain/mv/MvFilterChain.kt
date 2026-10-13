package com.yinyue.domain.mv

import com.yinyue.domain.normalize.AliasTable
import com.yinyue.domain.normalize.MetadataNormalizer
import com.yinyue.domain.normalize.VersionKeywords

/**
 * B站 MV 五步过滤链（v3.0 §5.3，Task 17，纯函数）：
 * ①type==video ②黑名单 ③标题匹配（歌名+歌手，走归一层别名表）④时长 [-5s,+40s] ⑤置信度排序。
 */
object MvFilterChain {

    /** 过滤链输入（与平台解耦的候选视图） */
    data class Candidate(
        val id: String,
        val title: String,
        val description: String = "",
        val durationSec: Long = 0,
        val author: String = "",
        val authorMid: Long = 0,
        val playCount: Long = 0,
        val official: Boolean = false,
        val type: String = "video",
        val coverUrl: String? = null,
    )

    data class Scored(
        val candidate: Candidate,
        val confidence: Float,
    )

    /** 合集类黑名单（长视频特征，任何场景都过滤） */
    private val COLLECTION_BLACKLIST = listOf(
        "合集", "合辑", "精选", "串烧", "连播", "歌单", "全曲", "纯享", "直播回放", "完整版合集", "专辑合集",
    )

    /** 版本/二创类黑名单（严格匹配"原唱 MV"时过滤；MV 关键词搜索时可保留） */
    private val VERSION_BLACKLIST = listOf(
        "翻唱", "cover", "reaction", "反应", "教程", "混剪", "remix", "二创", "教学向", "教学",
        "伴奏", "karaoke", "剪辑", "卡点", "切片", "演唱会", "巡演", "音乐会", "现场", "live",
    )

    /** 候选视频时长上限（秒）：超过视为合集/演唱会等长视频，直接丢弃 */
    const val DEFAULT_MAX_DURATION_SEC = 900L // 15 分钟

    fun filter(
        candidates: List<Candidate>,
        songName: String,
        artistName: String,
        entityDurationMs: Long?,
        alias: AliasTable = AliasTable.DEFAULT,
        durationRequired: Boolean = true, // MV-only 首搜无基准时长时可放宽（实体时长用 MV 兜底）
        maxDurationSec: Long = DEFAULT_MAX_DURATION_SEC,
        /** true=严格（过滤版本/二创类）；false=宽松（只过滤合集类，MV 关键词搜索用） */
        strictVersionFilter: Boolean = true,
    ): List<Scored> {
        val songNorm = MetadataNormalizer.normalizeSong(songName)
        val artistNorm = MetadataNormalizer.normalizeArtist(artistName, alias)
        val artistAliases = buildAliasCandidates(artistName, alias)
        val blacklist = if (strictVersionFilter) {
            COLLECTION_BLACKLIST + VERSION_BLACKLIST
        } else {
            COLLECTION_BLACKLIST
        }

        return candidates
            .asSequence()
            .filter { it.type == "video" } // ① 广告/合集过滤
            .filter { c -> // ② 黑名单（合集类总是过滤；版本类按 strict 决定）；UP主名也纳入（"XX精选集"类合集号）
                val text = (c.title + c.description + c.author).lowercase()
                blacklist.none { containsKeyword(text, it) } &&
                    VersionKeywords.matchedCategories(text)
                        .none { it == VersionKeywords.CAT_COVER || it == VersionKeywords.CAT_INSTRUMENTAL }
            }
            .filter { c -> // ③ 时长上限：滤掉合集/演唱会等长视频（几十分钟）
                c.durationSec in 1..maxDurationSec
            }
            .filter { c -> // ④ 标题匹配：歌名必须命中 + 歌手命中（归一后包含或别名）
                val titleNorm = MetadataNormalizer.normalizeSong(c.title)
                val titleLower = c.title.lowercase()
                val songHit = titleNorm.contains(songNorm)
                val artistHit = artistAliases.any { a -> a.isBlank() || titleLower.contains(a.lowercase()) || c.author.contains(a, ignoreCase = true) }
                songHit && artistHit
            }
            .filter { c -> // ⑤ 时长窗口 [-5s,+40s]；基准时长缺失直接丢弃（durationRequired 时）
                if (!durationRequired) return@filter true
                if (entityDurationMs == null) return@filter false
                val d = c.durationSec * 1000L
                d >= entityDurationMs - 5_000 && d <= entityDurationMs + 40_000
            }
            .map { it to confidence(it, songName, entityDurationMs) } // ⑥ 置信度
            .sortedByDescending { it.second }
            .map { (c, conf) -> Scored(c, conf) }
            .toList()
    }

    /**
     * 关键词命中判断：纯英文词用词边界（避免 "live" 误伤 alive/deliver、"cover" 误伤 discover），
     * 中文等直接用包含。
     */
    private fun containsKeyword(text: String, keyword: String): Boolean {
        return if (keyword.all { it.code < 128 }) {
            Regex("(?<![a-z])${Regex.escape(keyword)}(?![a-z])").containsMatchIn(text)
        } else {
            text.contains(keyword)
        }
    }

    /** 官方认证 > 标题含MV/官方 > 播放量 > 标题完全匹配；时长吻合附加 +0.10 */
    private fun confidence(c: Candidate, songName: String, entityDurationMs: Long?): Float {
        var score = 0f
        if (c.official) score += 0.40f
        val titleLower = c.title.lowercase()
        if (titleLower.contains("mv") || c.title.contains("官方")) score += 0.15f
        val titleNorm = MetadataNormalizer.normalizeSong(c.title)
        if (titleNorm == MetadataNormalizer.normalizeSong(songName)) score += 0.20f
        val playScore = (Math.log10((c.playCount + 1).toDouble()) / 6.0 * 0.25).coerceIn(0.0, 0.25).toFloat()
        score += playScore
        if (entityDurationMs != null && Math.abs(c.durationSec * 1000 - entityDurationMs) <= 5_000) score += 0.10f
        return score.coerceIn(0f, 1f)
    }

    /** 歌手候选：原名/多艺人 + 别名表正反查（标题含"Jay Chou"也能命中"周杰伦"） */
    private fun buildAliasCandidates(artistName: String, alias: AliasTable): List<String> {
        val parts = artistName.split(Regex("[/、,，&]")).map { it.trim() }.filter { it.isNotEmpty() }
        val canonicals = parts.map { alias.canonicalOf(it.lowercase()) ?: it } + artistName
        return alias.allNamesFor(canonicals).distinct()
    }
}
