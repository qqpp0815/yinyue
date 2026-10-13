package com.yinyue.player.data.source.model

/** 排行榜中的一个榜单（平台内置，如网易云「热歌榜」） */
data class TopListBoard(
    val platform: String,
    val boardId: String,
    val name: String,
)

/** 推荐歌单摘要（最新 / 最热） */
data class PlaylistBrief(
    val platform: String,
    val playlistId: String,
    val name: String,
    val coverUrl: String? = null,
    val playCount: Long = 0,
    val trackCount: Int = 0,
)

/** 歌单/榜单的歌曲集合（标题 + 曲目） */
data class TrackCollection(
    val title: String,
    val platform: String,
    val coverUrl: String? = null,
    val tracks: List<RawTrack> = emptyList(),
)
