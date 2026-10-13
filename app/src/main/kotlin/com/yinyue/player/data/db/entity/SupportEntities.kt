package com.yinyue.player.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 搜索结果缓存：cacheKey = normalizedKey + searchMode，24h 过期（v3.0 §6.1） */
@Entity(tableName = "search_cache", indices = [Index("expireAt")])
data class SearchCacheEntity(
    @PrimaryKey val cacheKey: String,
    val resultJson: String,
    val expireAt: Long,
)

/** 歌单（实体级，双形态共享） */
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int = 0,
)

/** 歌单↔歌曲多对多，position 用于拖拽排序 */
@Entity(
    tableName = "playlist_music_ref",
    primaryKeys = ["playlistId", "musicId"],
    indices = [Index("musicId"), Index("playlistId", "position")],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MusicEntity::class,
            parentColumns = ["id"],
            childColumns = ["musicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistMusicRef(
    val playlistId: Long,
    val musicId: Long,
    val position: Int,
)

/** 用户导入的洛雪脚本（APP 不内置不分发脚本，合规条款） */
@Entity(tableName = "user_script")
data class UserScriptEntity(
    @PrimaryKey val id: String,            // user_api_<随机>_<时间戳>，对齐 lx 规则
    val name: String,
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    val version: String = "",
    val scriptText: String,
    val enabled: Boolean = true,
    val priority: Int = 0,                 // 用户排序，取链排序依据之一
    val createdAt: Long,
    val lastTestAt: Long? = null,
    val lastTestOk: Boolean? = null,
    /** 在线导入的来源地址；非空才可"检查更新"（本地/剪贴板导入为空） */
    val sourceUrl: String = "",
    /** 落雪对齐：是否允许该脚本上报更新提醒（导入默认允许，用户可关） */
    val allowShowUpdateAlert: Boolean = true,
    /** 脚本通过 lx.send(updateAlert) 上报的更新信息（原始 JSON，空=无上报） */
    val reportedUpdateJson: String = "",
)

/** 最近播放 */
@Entity(
    tableName = "play_record",
    indices = [Index("musicId"), Index("playedAt")],
    foreignKeys = [
        ForeignKey(
            entity = MusicEntity::class,
            parentColumns = ["id"],
            childColumns = ["musicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlayRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val musicId: Long,
    val playedAt: Long,
)

/** 搜索历史（keyword 为主键，重复搜索刷新时间） */
@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey val keyword: String,
    val searchedAt: Long,
)
