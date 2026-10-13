package com.yinyue.player.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MV 链接（音源2/兜底产出，v3.0 §10）：B站 bilibili / 抖音解析 douyin_parse / 手动 manual。
 */
@Entity(
    tableName = "mv_link",
    indices = [Index("musicId"), Index("sourcePlatform", "remoteId")],
    foreignKeys = [
        ForeignKey(
            entity = MusicEntity::class,
            parentColumns = ["id"],
            childColumns = ["musicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MvLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val musicId: Long,
    val sourcePlatform: String,
    val remoteId: String? = null,          // bvid / aweme_id（失效重解析用）
    val title: String,
    val coverUrl: String? = null,
    val upOwner: String? = null,
    val matchConfidence: Float? = null,    // B站过滤链置信度
    val localAudioPath: String? = null,    // DASH 音轨落盘（video_store/{musicId}/audio.m4s）
    val localVideoPath: String? = null,    // 画面流（可空，按需缓存）
    val cacheState: CacheState = CacheState.REMOTE,
    val cachedAt: Long? = null,            // LRU 依据
)
