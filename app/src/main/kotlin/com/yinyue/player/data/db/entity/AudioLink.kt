package com.yinyue.player.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 缓存状态三态：本地文件 / 在线可用 / 已失效 */
enum class CacheState { LOCAL, REMOTE, EXPIRED }

/**
 * 音频链接（音源1 产出，v3.0 §10）。
 * 排序规则「音质 > 脚本优先级 > 响应时间」需要三个冗余列：
 * qualityLevel（flac24bit/flac=4、320k=3、128k=2、unknown=1）、
 * scriptPriority（导入脚本的用户排序）、responseTimeMs（取链耗时）。
 */
@Entity(
    tableName = "audio_link",
    indices = [Index("musicId"), Index("platform", "remoteTrackId")],
    foreignKeys = [
        ForeignKey(
            entity = MusicEntity::class,
            parentColumns = ["id"],
            childColumns = ["musicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AudioLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val musicId: Long,
    val scriptId: String,                  // 洛雪脚本标识
    val platform: String,                  // kw / kg / tx / wy / mg / local
    val remoteTrackId: String,
    /** 演唱者归一核心键：用于"以原唱为主"——原唱链接全保留，他人翻唱限量且不自动播放 */
    val artistKey: String = "",
    val bitrate: Int? = null,
    val qualityLevel: Int = 1,
    val originTag: Int = -1,               // 原唱=1 / 翻唱=0 / 未知=-1（优先级打分依据）
    val priorityScore: Int = 0,            // 冗余排序分（PriorityScorer 计算）
    val scriptPriority: Int = 0,
    val responseTimeMs: Long? = null,
    val localPath: String? = null,         // 本地文件优先（存文件不存链接）
    val cacheState: CacheState = CacheState.REMOTE,
    val lastVerified: Long? = null,        // 播放前新鲜度校验依据
    /** 洛雪旧版 musicInfo JSON（搜索/榜单建链时落库；起播取链不再补搜平台元数据，冷启动也秒级） */
    val musicInfoJson: String? = null,
    /**
     * 锁定直链（A+：加入歌单后后台自适应切源，搜到最优源即锁定）。
     * resolvedUrl/resolvedAt/resolvedQuality/resolvedScriptId 四列一组，30 分钟内直接复用，
     * 不调脚本不补搜；直链失效/过期 → 自动重解析并刷新锁定。仅供流播，落盘文件仍走 localPath。
     */
    val resolvedUrl: String? = null,
    val resolvedAt: Long? = null,
    val resolvedQuality: String? = null,
    val resolvedScriptId: String? = null,
)
