package com.yinyue.player.domain.search

import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.MusicEntity

/** 搜索模式（v3.0 §6）：只搜歌 / 只搜MV / 都搜 */
enum class SearchMode { AUDIO_ONLY, VIDEO_ONLY, BOTH }

data class UnifiedSearchRequest(
    val keyword: String,
    val mode: SearchMode = SearchMode.BOTH,
    val page: Int = 1,
)

/**
 * 歌曲结果（音源1 音频线）：一个实体 + 其有序音源列表。
 * 与 MV 结果完全独立（音频/MV 两线互不干扰）。
 */
data class SongResult(
    val music: MusicEntity,
    val audioLinks: List<AudioLink>,
)

/**
 * MV 结果（音源2 B站线）：独立条目，不挂歌曲实体。
 * 用户自行决定是否加入播放列表。
 */
data class MvResult(
    val bvid: String,
    val title: String,
    val author: String,
    val durationSec: Long,
    val play: Long,
    val confidence: Float,
    val coverUrl: String? = null,
    val matchedMusicId: Long? = null, // 若搜索时命中了音频实体则记录（仅用于展示关联，不影响独立播放）
)

data class SearchResultPage(
    val keyword: String,
    val mode: SearchMode,
    val songs: List<SongResult>,
    val mvResults: List<MvResult>,
    val failedPlatforms: List<String>, // 失败隔离：UI 标"暂不可用"
    val fromCache: Boolean = false,
    /** 非空表示本次为"歌手搜索"：展示该歌手去重后的全部歌曲（UI 显示歌手卡片） */
    val artistName: String? = null,
)
