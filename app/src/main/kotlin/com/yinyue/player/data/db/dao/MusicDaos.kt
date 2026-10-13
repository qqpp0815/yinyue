package com.yinyue.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.OriginalState
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM music_entity WHERE normalizedKey = :key LIMIT 1")
    suspend fun findByNormalizedKey(key: String): MusicEntity?

    /** Levenshtein 兜底用：同归一歌手的全部实体 */
    @Query("SELECT * FROM music_entity WHERE normalizedArtist = :artistKey")
    suspend fun findAllByArtistKey(artistKey: String): List<MusicEntity>

    @Query("SELECT * FROM music_entity WHERE id = :id")
    suspend fun findById(id: Long): MusicEntity?

    @Query("SELECT * FROM music_entity WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<MusicEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MusicEntity): Long

    @Update
    suspend fun update(entity: MusicEntity)

    @Query("UPDATE music_entity SET verifiedOriginal = :state, originalScore = :score WHERE id = :id")
    suspend fun updateVerified(id: Long, state: OriginalState, score: Float?)

    @Query("UPDATE music_entity SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("SELECT * FROM music_entity WHERE favorite = 1 ORDER BY createdAt DESC")
    fun favorites(): Flow<List<MusicEntity>>

    /** 收藏实体 id（缓存保护集的输入：收藏的音频/视频缓存不参与 LRU 淘汰） */
    @Query("SELECT id FROM music_entity WHERE favorite = 1")
    suspend fun favoriteIds(): List<Long>

    @Query("SELECT COUNT(*) FROM music_entity")
    suspend fun count(): Int

    /** 最近创建的实体（合并/拆分工具选源用） */
    @Query("SELECT * FROM music_entity ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MusicEntity>

    /// 待人工确认的原唱队列（v3.0 设置页·验证：待定项人工确认队列）
    @Query("SELECT * FROM music_entity WHERE verifiedOriginal = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun pendingVerification(limit: Int = 200): List<MusicEntity>

    @Query("SELECT COUNT(*) FROM music_entity WHERE verifiedOriginal = 'PENDING'")
    suspend fun countPendingVerification(): Int

    @Query("DELETE FROM music_entity WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface AudioLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: AudioLink): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<AudioLink>)

    /** 实体内多链接排序：优先级分（音质×10+原唱×2+来源权重）> 脚本优先级 > 响应时间（v1.0 §5.2 + v3.0 §3.3） */
    @Query(
        "SELECT * FROM audio_link WHERE musicId = :musicId " +
            "ORDER BY priorityScore DESC, scriptPriority DESC, responseTimeMs IS NULL, responseTimeMs ASC, id ASC",
    )
    suspend fun listByMusicOrdered(musicId: Long): List<AudioLink>

    @Query("SELECT * FROM audio_link WHERE musicId = :musicId AND cacheState != 'EXPIRED'")
    suspend fun listValidByMusic(musicId: Long): List<AudioLink>

    /**
     * 有效链接的优先级序（起播/切源/歌词一致按此顺序尝试，见 listByMusicOrdered 的排序规则）。
     * 「失效」只对需要联网的链接成立：已落盘（localPath 非空）的链接即使被标 EXPIRED 也带回来，
     * 由播放侧用本地文件自愈（否则一次联网失败会让下载/兜底导入的文件永久无法离线播放）。
     */
    @Query(
        "SELECT * FROM audio_link WHERE musicId = :musicId " +
            "AND (cacheState != 'EXPIRED' OR localPath IS NOT NULL) " +
            "ORDER BY priorityScore DESC, scriptPriority DESC, responseTimeMs IS NULL, responseTimeMs ASC, id ASC",
    )
    suspend fun listValidByMusicOrdered(musicId: Long): List<AudioLink>

    @Query("SELECT * FROM audio_link WHERE cacheState = 'EXPIRED' ORDER BY id ASC")
    suspend fun listExpired(): List<AudioLink>

    @Query("SELECT * FROM audio_link WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AudioLink?

    @Query("SELECT * FROM audio_link WHERE localPath = :localPath LIMIT 1")
    suspend fun findByLocalPath(localPath: String): AudioLink?

    /** 元数据补搜结果落库：冷启动首播补搜一次后就固化，避免每次起播重复联网 */
    @Query("UPDATE audio_link SET musicInfoJson = :json WHERE id = :id")
    suspend fun updateMusicInfo(id: Long, json: String)
    @Query("SELECT * FROM audio_link WHERE platform = :platform AND remoteTrackId = :remoteTrackId LIMIT 1")
    suspend fun findByPlatformTrack(platform: String, remoteTrackId: String): AudioLink?

    @Query("UPDATE audio_link SET cacheState = :state WHERE id = :id")
    suspend fun updateCacheState(id: Long, state: CacheState)

    @Query("UPDATE audio_link SET localPath = :localPath, cacheState = :state WHERE id = :id")
    suspend fun updateLocalPath(id: Long, localPath: String?, state: CacheState)

    /** 回填演唱者（artistKey 字段是 v4 新增，旧链接为空） */
    @Query("UPDATE audio_link SET artistKey = :key WHERE id = :id")
    suspend fun updateArtistKey(id: Long, key: String)

    /** 修正音质档位（历史 bug：bestQuality 按枚举序号比较，取到了最差档） */
    @Query("UPDATE audio_link SET qualityLevel = :level WHERE id = :id")
    suspend fun updateQualityLevel(id: Long, level: Int)

    /** 重算优先级分（公式改为"原唱×100 + 音质×10 + 权重"后，旧数据需要刷新） */
    @Query("UPDATE audio_link SET priorityScore = :score WHERE id = :id")
    suspend fun updatePriorityScore(id: Long, score: Int)

    @Query("UPDATE audio_link SET lastVerified = :ts WHERE id = :id")
    suspend fun updateLastVerified(id: Long, ts: Long)

    /** 写入/清除锁定直链（A+：后台锁定最优源，30min 内起播直接复用） */
    @Query(
        "UPDATE audio_link SET resolvedUrl = :url, resolvedAt = :atMs, " +
            "resolvedQuality = :quality, resolvedScriptId = :scriptId WHERE id = :id",
    )
    suspend fun updateResolved(id: Long, url: String?, atMs: Long?, quality: String?, scriptId: String?)

    @Query("DELETE FROM audio_link WHERE musicId = :musicId")
    suspend fun deleteByMusicId(musicId: Long)

    /** 链接过多的实体（全库整理用：同名合并后可能存在上百条链接的实体） */
    @Query("SELECT musicId FROM audio_link GROUP BY musicId HAVING COUNT(*) > :threshold")
    suspend fun musicIdsWithManyLinks(threshold: Int): List<Long>

    @Query("DELETE FROM audio_link WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface MvLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: MvLink): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<MvLink>)

    @Query("SELECT * FROM mv_link WHERE musicId = :musicId ORDER BY matchConfidence IS NULL, matchConfidence DESC")
    suspend fun listByMusic(musicId: Long): List<MvLink>

    @Query("SELECT * FROM mv_link WHERE musicId = :musicId AND sourcePlatform = :platform LIMIT 1")
    suspend fun findByPlatform(musicId: Long, platform: String): MvLink?

    @Query("SELECT * FROM mv_link WHERE sourcePlatform = :platform AND remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(platform: String, remoteId: String): MvLink?

    @Query("SELECT * FROM mv_link")
    suspend fun allOnce(): List<MvLink>

    @Query("UPDATE mv_link SET cacheState = :state, cachedAt = :cachedAt WHERE id = :id")
    suspend fun updateCacheState(id: Long, state: CacheState, cachedAt: Long?)

    @Query("UPDATE mv_link SET localAudioPath = :audioPath, localVideoPath = :videoPath, cacheState = :state WHERE id = :id")
    suspend fun updateLocalPaths(id: Long, audioPath: String?, videoPath: String?, state: CacheState)

    @Query("DELETE FROM mv_link WHERE musicId = :musicId")
    suspend fun deleteByMusicId(musicId: Long)
}
