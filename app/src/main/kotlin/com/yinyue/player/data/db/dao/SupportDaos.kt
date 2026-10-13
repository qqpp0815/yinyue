package com.yinyue.player.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.PlayRecord
import com.yinyue.player.data.db.entity.Playlist
import com.yinyue.player.data.db.entity.PlaylistMusicRef
import com.yinyue.player.data.db.entity.SearchCacheEntity
import com.yinyue.player.data.db.entity.SearchHistory
import com.yinyue.player.data.db.entity.UserScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchCacheDao {
    @Query("SELECT * FROM search_cache WHERE cacheKey = :cacheKey AND expireAt > :now LIMIT 1")
    suspend fun getValid(cacheKey: String, now: Long): SearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SearchCacheEntity)

    @Query("DELETE FROM search_cache WHERE expireAt <= :now")
    suspend fun purgeExpired(now: Long)

    @Query("DELETE FROM search_cache")
    suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlist ORDER BY sortOrder ASC, createdAt DESC")
    fun all(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun allOnce(): List<Playlist>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun findById(id: Long): Playlist?

    @Query("SELECT COUNT(*) FROM playlist_music_ref WHERE playlistId = :playlistId")
    suspend fun songCount(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSong(ref: PlaylistMusicRef)

    @Query("DELETE FROM playlist_music_ref WHERE playlistId = :playlistId AND musicId = :musicId")
    suspend fun removeSong(playlistId: Long, musicId: Long)

    @Query("DELETE FROM playlist_music_ref WHERE playlistId = :playlistId")
    suspend fun clearSongs(playlistId: Long)

    @Query(
        "SELECT music_entity.* FROM music_entity " +
            "INNER JOIN playlist_music_ref ON music_entity.id = playlist_music_ref.musicId " +
            "WHERE playlist_music_ref.playlistId = :playlistId " +
            "ORDER BY playlist_music_ref.position ASC",
    )
    suspend fun songsOf(playlistId: Long): List<MusicEntity>

    @Query("SELECT * FROM playlist_music_ref WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun refsOf(playlistId: Long): List<PlaylistMusicRef>

    @Update
    suspend fun updateRefs(refs: List<PlaylistMusicRef>)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_music_ref WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int
}

@Dao
interface UserScriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: UserScriptEntity)

    @Query("SELECT * FROM user_script ORDER BY priority DESC, createdAt DESC")
    fun all(): Flow<List<UserScriptEntity>>

    @Query("SELECT * FROM user_script ORDER BY priority DESC, createdAt DESC")
    suspend fun allOnce(): List<UserScriptEntity>

    @Query("SELECT * FROM user_script WHERE enabled = 1 ORDER BY priority DESC, createdAt DESC")
    suspend fun enabledScripts(): List<UserScriptEntity>

    @Query("SELECT * FROM user_script WHERE id = :id")
    suspend fun findById(id: String): UserScriptEntity?

    @Query("UPDATE user_script SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE user_script SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: String, priority: Int)

    @Query("UPDATE user_script SET lastTestAt = :ts, lastTestOk = :ok WHERE id = :id")
    suspend fun updateTestResult(id: String, ts: Long, ok: Boolean)

    @Query("UPDATE user_script SET allowShowUpdateAlert = :allow WHERE id = :id")
    suspend fun setAllowUpdateAlert(id: String, allow: Boolean)

    @Query("UPDATE user_script SET reportedUpdateJson = :json WHERE id = :id")
    suspend fun setReportedUpdate(id: String, json: String)

    /** 应用更新：替换脚本内容并刷新头注释解析出的元信息 */
    @Query(
        "UPDATE user_script SET scriptText = :text, name = :name, description = :description, " +
            "author = :author, homepage = :homepage, version = :version, " +
            "reportedUpdateJson = '' WHERE id = :id",
    )
    suspend fun applyScriptUpdate(
        id: String,
        text: String,
        name: String,
        description: String,
        author: String,
        homepage: String,
        version: String,
    )

    @Query("DELETE FROM user_script WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlayRecordDao {
    @Insert
    suspend fun record(record: PlayRecord)

    @Query(
        "SELECT music_entity.* FROM music_entity " +
            "INNER JOIN play_record ON music_entity.id = play_record.musicId " +
            "GROUP BY music_entity.id ORDER BY MAX(play_record.playedAt) DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int = 50): List<MusicEntity>
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: SearchHistory)

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<SearchHistory>

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
