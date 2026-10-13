package com.yinyue.player.data.source.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 音质枚举（对齐 lx 白名单 128k/320k/flac/flac24bit） */
enum class QualityType(val lxName: String, val level: Int) {
    FLAC24BIT("flac24bit", 4),
    FLAC("flac", 4),
    K320("320k", 3),
    K128("128k", 2),
    UNKNOWN("unknown", 1),
    ;

    companion object {
        fun fromLx(name: String): QualityType = entries.firstOrNull { it.lxName == name } ?: UNKNOWN
    }
}

data class TrackQuality(val type: QualityType, val sizeLabel: String? = null)

/**
 * 统一搜索结果条目（音源1 元数据层产出）。
 * extras 保存平台扩展字段（kg: hash/albumAudioId，tx: strMediaMid/albumMid，mg: copyrightId 等），
 * 取链时用于拼装洛雪旧版 musicInfo（对齐 lx toOldMusicInfo）。
 */
data class RawTrack(
    val platform: String,
    val remoteTrackId: String,
    val songName: String,
    val artistName: String, // 已按 lx formatSinger 把 & 换成 、
    val albumName: String? = null,
    val albumId: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null, // 搜索结果直接携带封面的平台填充（wy/tx/mg）
    val qualitys: List<TrackQuality> = emptyList(),
    val extras: Map<String, String?> = emptyMap(),
) {
    /**
     * 平台元数据里标注的最高音质。
     *
     * 注意必须按 `level` 比，**不能按枚举序号**：本枚举的声明顺序是"高→低"（FLAC24BIT/FALC/K320/K128/UNKNOWN），
     * 序号与音质刚好相反，用 `maxOfOrNull { it.type }` 会取到**最差**的那档（有 UNKNOWN 就取 UNKNOWN，否则取 128K）。
     * 这个 bug 曾导致所有链接都标 128K、并且取链只试 128K。
     */
    val bestQuality: QualityType
        get() = qualitys.maxByOrNull { it.type.level }?.type ?: QualityType.UNKNOWN

    /**
     * 洛雪旧版 musicInfo JSON（对齐 lx-music-desktop common/utils/tools.ts 的 toOldMusicInfo），
     * 供洛雪脚本 request(action='musicUrl') 取链使用。
     */
    fun toOldMusicInfoJson(version: String = ""): JsonObject {
        val obj = JsonObject().apply {
            addProperty("songmid", remoteTrackId)
            addProperty("name", songName)
            addProperty("singer", artistName)
            addProperty("source", platform)
            addProperty("interval", intervalLabel)
            addProperty("albumName", albumName ?: "")
            addProperty("albumId", albumId ?: "")
            addProperty("img", "")
            add("typeUrl", JsonObject())
            val types = JsonArray()
            val typesMap = JsonObject()
            qualitys.forEach { q ->
                types.add(JsonObject().apply {
                    addProperty("type", q.type.lxName)
                    q.sizeLabel?.let { addProperty("size", it) }
                })
                typesMap.add(q.type.lxName, JsonObject().apply {
                    q.sizeLabel?.let { addProperty("size", it.uppercase()) }
                })
            }
            add("types", types)
            add("_types", typesMap)
            when (platform) {
                "kw" -> {
                    addProperty("songId", remoteTrackId)
                    addProperty("copyrightId", "")
                    addProperty("hash", "")
                    addProperty("albumId", albumId ?: "")
                    addProperty("priority", 0)
                    addProperty("quality", bestQuality.lxName)
                    addProperty("typeFile", "")
                    addProperty("extension", "")
                    addProperty("fileName", "")
                    addProperty("isMohu", false)
                    addProperty("isMutate", false)
                    addProperty("otherFileName", "")
                }
                "kg" -> {
                    addProperty("hash", extras["hash"] ?: remoteTrackId)
                    addProperty("albumId", extras["albumAudioId"] ?: albumId ?: "")
                    addProperty("songId", extras["albumAudioId"] ?: remoteTrackId)
                    addProperty("copyrightId", "")
                    addProperty("priority", 0)
                    addProperty("quality", bestQuality.lxName)
                    addProperty("typeFile", extras["typeFile"] ?: "")
                    addProperty("extension", extras["extension"] ?: "")
                    addProperty("fileName", "")
                    addProperty("isMohu", false)
                    addProperty("isMutate", false)
                    addProperty("otherFileName", "")
                }
                "tx" -> {
                    addProperty("songmid", remoteTrackId)
                    addProperty("songId", extras["songId"] ?: "")
                    addProperty("strMediaMid", extras["strMediaMid"] ?: "")
                    addProperty("albumMid", extras["albumMid"] ?: albumId ?: "")
                    addProperty("albumId", extras["albumMid"] ?: albumId ?: "")
                    addProperty("copyrightId", "")
                    addProperty("hash", "")
                    addProperty("priority", 0)
                    addProperty("quality", bestQuality.lxName)
                    addProperty("typeFile", "")
                    addProperty("extension", "")
                    addProperty("fileName", "")
                    addProperty("isMohu", false)
                    addProperty("isMutate", false)
                    addProperty("otherFileName", "")
                }
                "wy" -> {
                    addProperty("songId", remoteTrackId)
                    addProperty("copyrightId", "")
                    addProperty("hash", "")
                    addProperty("albumId", albumId ?: "")
                    addProperty("priority", 0)
                    addProperty("quality", bestQuality.lxName)
                    addProperty("typeFile", "")
                    addProperty("extension", "")
                    addProperty("fileName", "")
                    addProperty("isMohu", false)
                    addProperty("isMutate", false)
                    addProperty("otherFileName", "")
                }
                "mg" -> {
                    addProperty("copyrightId", extras["copyrightId"] ?: remoteTrackId)
                    addProperty("songId", remoteTrackId)
                    addProperty("hash", "")
                    addProperty("albumId", albumId ?: "")
                    addProperty("lrcUrl", extras["lrcUrl"] ?: "")
                    addProperty("mrcUrl", extras["mrcUrl"] ?: "")
                    addProperty("trcUrl", extras["trcUrl"] ?: "")
                    addProperty("priority", 0)
                    addProperty("quality", bestQuality.lxName)
                    addProperty("typeFile", "")
                    addProperty("extension", "")
                    addProperty("fileName", "")
                    addProperty("isMohu", false)
                    addProperty("isMutate", false)
                    addProperty("otherFileName", "")
                }
            }
            if (version.isNotBlank()) addProperty("version", version)
            extras.forEach { (k, v) -> if (v != null) addProperty(k, v) }
        }
        return obj
    }

    private val intervalLabel: String
        get() = durationMs?.let {
            val totalSec = it / 1000
            String.format("%02d:%02d", totalSec / 60, totalSec % 60)
        } ?: "00:00"
}

data class SearchPage(
    val list: List<RawTrack>,
    val page: Int,
    val total: Int,
    val allPage: Int,
)
