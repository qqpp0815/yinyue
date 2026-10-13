package com.yinyue.player.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原唱验证状态（v3.0 §4.2，结论挂实体级）。
 * 用户人工确认权重最高、覆盖算法结论：
 * USER_CONFIRMED = 人工确认原唱；USER_MARKED_COVER = 人工标为翻唱。
 */
enum class OriginalState { ORIGINAL, PENDING, SUSPECT_COVER, USER_CONFIRMED, USER_MARKED_COVER }

/**
 * MusicEntity —— 唯一真相（v3.0 §10）。
 * 收藏/验证/歌单全部实体级；双源（AudioLink/MvLink）都挂在 musicId 上。
 */
@Entity(
    tableName = "music_entity",
    indices = [Index("normalizedKey"), Index("normalizedSong"), Index("favorite")],
)
data class MusicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songName: String,
    val artistName: String,
    val albumName: String? = null,
    val coverUrl: String? = null,          // 封面地址（平台元数据，播放页/列表展示）
    val durationMs: Long? = null,          // 音源1 元数据优先，B站兜底
    val normalizedKey: String,             // 归一匹配键（当前去重模式）
    val normalizedSong: String,            // 归一歌名（Levenshtein 兜底/拆分用）
    val normalizedArtist: String,          // 归一歌手（多艺人排序拼接）
    val verifiedOriginal: OriginalState = OriginalState.PENDING,
    val originalScore: Float? = null,      // 双源融合验证分 0~1
    val defaultSourceId: String? = null,   // 单首默认源（"platform:remoteTrackId"，v1.0 Song 表字段）
    val favorite: Boolean = false,         // 收藏（缓存保护 + 全形态共享）
    val createdAt: Long,
)
