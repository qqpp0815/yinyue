package com.yinyue.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yinyue.player.data.db.dao.AudioLinkDao
import com.yinyue.player.data.db.dao.MvLinkDao
import com.yinyue.player.data.db.dao.MusicDao
import com.yinyue.player.data.db.dao.PlayRecordDao
import com.yinyue.player.data.db.dao.PlaylistDao
import com.yinyue.player.data.db.dao.SearchCacheDao
import com.yinyue.player.data.db.dao.SearchHistoryDao
import com.yinyue.player.data.db.dao.UserScriptDao
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.PlayRecord
import com.yinyue.player.data.db.entity.Playlist
import com.yinyue.player.data.db.entity.PlaylistMusicRef
import com.yinyue.player.data.db.entity.SearchCacheEntity
import com.yinyue.player.data.db.entity.SearchHistory
import com.yinyue.player.data.db.entity.UserScriptEntity

@Database(
    entities = [
        MusicEntity::class,
        AudioLink::class,
        MvLink::class,
        SearchCacheEntity::class,
        Playlist::class,
        PlaylistMusicRef::class,
        UserScriptEntity::class,
        PlayRecord::class,
        SearchHistory::class,
    ],
    version = 5, // v4: artistKey；v5: musicInfoJson + 锁定直链四列（起播不再补搜/取链）
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun audioLinkDao(): AudioLinkDao
    abstract fun mvLinkDao(): MvLinkDao
    abstract fun searchCacheDao(): SearchCacheDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun userScriptDao(): UserScriptDao
    abstract fun playRecordDao(): PlayRecordDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        /**
         * v2 → v3：user_script 增加脚本更新相关三列。
         * NOT NULL 列必须给 DEFAULT，否则已有行的 ALTER TABLE 会失败。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_script ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE user_script ADD COLUMN allowShowUpdateAlert INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE user_script ADD COLUMN reportedUpdateJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 → v4：audio_link 增加 artistKey（"以原唱为主"的判定依据） */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_link ADD COLUMN artistKey TEXT NOT NULL DEFAULT ''")
                // 顺带清空搜索缓存：让每个关键词下次搜索重新合并一次，
                // 从而为旧链接回填演唱者并按"原唱为主"裁剪（旧数据的 artistKey 只能这样补出来）
                db.execSQL("DELETE FROM search_cache")
            }
        }

        /**
         * v4 → v5：audio_link 增加五列：
         * musicInfoJson（搜索建链时落库，起播不再补搜平台元数据）
         * resolvedUrl / resolvedAt / resolvedQuality / resolvedScriptId（锁定直链，30min 内直接复用）
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_link ADD COLUMN musicInfoJson TEXT")
                db.execSQL("ALTER TABLE audio_link ADD COLUMN resolvedUrl TEXT")
                db.execSQL("ALTER TABLE audio_link ADD COLUMN resolvedAt INTEGER")
                db.execSQL("ALTER TABLE audio_link ADD COLUMN resolvedQuality TEXT")
                db.execSQL("ALTER TABLE audio_link ADD COLUMN resolvedScriptId TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yinyue.db",
                )
                    // v3：user_script 三列；v4：audio_link.artistKey；v5：audio_link 元数据+锁定直链。
                    // 必须走真迁移——重建会连"用户导入的脚本 / 歌单 / 实体"一起清掉
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration() // 兜底：未覆盖的变更才重建（缓存可重下）
                    .build().also { instance = it }
            }
    }
}
