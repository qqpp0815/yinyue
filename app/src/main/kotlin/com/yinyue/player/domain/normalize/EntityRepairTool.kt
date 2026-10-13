package com.yinyue.player.domain.normalize

import com.yinyue.domain.normalize.MetadataNormalizer
import com.yinyue.domain.normalize.NameCleaner
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.MusicEntity

/**
 * 实体合并/拆分工具（v3.0 §13 R7 对策）：
 * 归一层误判时的手动修复——把误拆的两个实体合并，或把误并的链接拆到新实体。
 */
object EntityRepairTool {

    data class MergeResult(val movedLinks: Int, val mergedInto: Long)

    /**
     * 合并实体：把 from 的音源链接迁移到 to（去重平台+曲目），删除 from。
     * 收藏取并集（任一收藏则结果收藏），时长/封面取先有值者。
     */
    suspend fun merge(db: AppDatabase, fromId: Long, toId: Long): MergeResult? {
        if (fromId == toId) return null
        val from = db.musicDao().findById(fromId) ?: return null
        val to = db.musicDao().findById(toId) ?: return null

        val toLinks = db.audioLinkDao().listByMusicOrdered(toId)
        val fromLinks = db.audioLinkDao().listByMusicOrdered(fromId)
        val existing = toLinks.map { it.platform to it.remoteTrackId }.toSet()
        var moved = 0
        for (link in fromLinks) {
            if ((link.platform to link.remoteTrackId) in existing) continue
            db.audioLinkDao().insert(
                link.copy(id = 0, musicId = toId, scriptId = link.scriptId),
            )
            moved++
        }
        // MV 链接同样迁移
        db.mvLinkDao().listByMusic(fromId).forEach { mv ->
            db.mvLinkDao().insert(mv.copy(id = 0, musicId = toId))
        }
        // 实体字段合并：收藏并集、缺失字段补齐
        db.musicDao().update(
            to.copy(
                favorite = to.favorite || from.favorite,
                durationMs = to.durationMs ?: from.durationMs,
                coverUrl = to.coverUrl ?: from.coverUrl,
                albumName = to.albumName ?: from.albumName,
            ),
        )
        // 删除源实体（级联清理其残留链接）
        db.musicDao().deleteByIds(listOf(fromId))
        return MergeResult(moved, toId)
    }

    /**
     * 拆分实体：把指定链接拆到新实体（歌名/歌手由用户给定）。
     * 返回新实体 id。
     */
    suspend fun split(
        db: AppDatabase,
        context: android.content.Context,
        musicId: Long,
        linkIds: List<Long>,
        newSongName: String,
        newArtistName: String,
    ): Long? {
        if (linkIds.isEmpty()) return null
        val settings = com.yinyue.player.data.prefs.SettingsRepository.get(context)
        val dedupMode = settings.dedupMode()
        val display = NameCleaner.coreSongName(newSongName)
        val newKey = SearchDispatcherKey.of(display, newArtistName, dedupMode)
        val newId = db.musicDao().insert(
            MusicEntity(
                songName = display,
                artistName = newArtistName,
                normalizedKey = newKey,
                normalizedSong = MetadataNormalizer.normalizeSong(display),
                normalizedArtist = MetadataNormalizer.normalizeArtist(newArtistName),
                createdAt = System.currentTimeMillis(),
            ),
        )
        val all = db.audioLinkDao().listByMusicOrdered(musicId)
        for (link in all) {
            if (link.id in linkIds) {
                db.audioLinkDao().insert(link.copy(id = 0, musicId = newId))
                db.audioLinkDao().deleteByIds(listOf(link.id))
            }
        }
        return newId
    }

    /** 供拆分计算去重键（复用 SearchDispatcher 的键规则） */
    private object SearchDispatcherKey {
        fun of(
            songName: String,
            artistName: String,
            mode: MetadataNormalizer.DedupMode,
        ): String {
            val core = NameCleaner.coreKey(songName)
            return when (mode) {
                MetadataNormalizer.DedupMode.SONG_ONLY -> core
                MetadataNormalizer.DedupMode.SONG_AND_ARTIST ->
                    core + "::" + MetadataNormalizer.normalizeArtist(artistName)
            }
        }
    }
}
