package com.yinyue.player.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yinyue.domain.normalize.MetadataNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 应用设置（去重模式/自动切源/缓存配额等，重启不丢） */
class SettingsRepository private constructor(private val context: Context) {

    object Keys {
        val DEDUP_MODE = stringPreferencesKey("dedup_mode") // SONG_ONLY / SONG_AND_ARTIST
        val AUTO_SWITCH_SOURCE = booleanPreferencesKey("auto_switch_source")
        val AUTO_SKIP_ON_ERROR = booleanPreferencesKey("auto_skip_on_error") // 播放失败自动跳下一首（对齐落雪 autoSkipOnError）
        val AUDIO_QUOTA_BYTES = longPreferencesKey("audio_quota_bytes")
        val VIDEO_QUOTA_BYTES = longPreferencesKey("video_quota_bytes")
        val CACHE_MV_VIDEO = booleanPreferencesKey("cache_mv_video")
        val DOUYIN_RELAY = stringPreferencesKey("douyin_relay")
        val USER_ALIASES = stringPreferencesKey("user_aliases")
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality") // flac24bit/flac/320k/128k
        val PLAY_MODE = stringPreferencesKey("play_mode") // LIST_LOOP/RANDOM/LIST/SINGLE_LOOP/NONE
        val DESKTOP_LYRIC = booleanPreferencesKey("desktop_lyric") // 桌面歌词（悬浮窗歌词）
        val DESKTOP_LYRIC_STYLE = stringPreferencesKey("desktop_lyric_style") // 桌面歌词样式(JSON)
        val BILI_RATE_LIMIT_MS = longPreferencesKey("bili_rate_limit_ms")
        val BILI_QUALITY = stringPreferencesKey("bili_quality") // AUTO / P480 / P720 / P1080
        val BILI_COOKIE = stringPreferencesKey("bili_cookie")   // 用户自备，仅本地保存
    }

    val dedupModeFlow: Flow<MetadataNormalizer.DedupMode> = context.dataStore.data.map { p ->
        when (p[Keys.DEDUP_MODE]) {
            "SONG_AND_ARTIST" -> MetadataNormalizer.DedupMode.SONG_AND_ARTIST
            else -> MetadataNormalizer.DedupMode.SONG_ONLY // 默认同名合并（v1.0 规则1：预期设计）
        }
    }

    val autoSwitchSourceFlow: Flow<Boolean> = context.dataStore.data.map { p -> p[Keys.AUTO_SWITCH_SOURCE] ?: true }

    suspend fun dedupMode(): MetadataNormalizer.DedupMode = dedupModeFlow.first()

    suspend fun autoSwitchSource(): Boolean = autoSwitchSourceFlow.first()

    suspend fun setDedupMode(mode: MetadataNormalizer.DedupMode) {
        context.dataStore.edit { it[Keys.DEDUP_MODE] = mode.name }
    }

    suspend fun setAutoSwitchSource(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SWITCH_SOURCE] = enabled }
    }

    /** 播放失败自动跳下一首（默认开；连续失败由 AutoSkipGuard 熔断） */
    val autoSkipOnErrorFlow: Flow<Boolean> = context.dataStore.data.map { p -> p[Keys.AUTO_SKIP_ON_ERROR] ?: true }

    suspend fun autoSkipOnError(): Boolean = autoSkipOnErrorFlow.first()

    suspend fun setAutoSkipOnError(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SKIP_ON_ERROR] = enabled }
    }

    suspend fun audioQuotaBytes(): Long =
        context.dataStore.data.map { p -> p[Keys.AUDIO_QUOTA_BYTES] ?: 5L * 1024 * 1024 * 1024 }.first()

    suspend fun setAudioQuotaBytes(bytes: Long) {
        context.dataStore.edit { it[Keys.AUDIO_QUOTA_BYTES] = bytes }
    }

    suspend fun videoQuotaBytes(): Long =
        context.dataStore.data.map { p -> p[Keys.VIDEO_QUOTA_BYTES] ?: 10L * 1024 * 1024 * 1024 }.first()

    suspend fun setVideoQuotaBytes(bytes: Long) {
        context.dataStore.edit { it[Keys.VIDEO_QUOTA_BYTES] = bytes }
    }

    suspend fun cacheMvVideo(): Boolean =
        context.dataStore.data.map { p -> p[Keys.CACHE_MV_VIDEO] ?: false }.first()

    suspend fun setCacheMvVideo(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CACHE_MV_VIDEO] = enabled }
    }

    suspend fun douyinRelay(): String =
        context.dataStore.data.map { p -> p[Keys.DOUYIN_RELAY] ?: "" }.first()

    suspend fun setDouyinRelay(url: String) {
        context.dataStore.edit { it[Keys.DOUYIN_RELAY] = url.trim() }
    }

    /**
     * 优先播放音质（对齐落雪 player.playQuality，默认 128k）。
     * 取链时从该档向下找，不向上越档；实际拿到什么档由平台与脚本能力决定。
     */
    suspend fun preferredQuality(): String =
        context.dataStore.data.map { p ->
            p[Keys.PREFERRED_QUALITY]?.takeIf { it in com.yinyue.domain.match.QualityPreference.CHOICES }
                ?: com.yinyue.domain.match.QualityPreference.DEFAULT
        }.first()

    suspend fun setPreferredQuality(quality: String) {
        context.dataStore.edit { it[Keys.PREFERRED_QUALITY] = quality }
    }

    /** 桌面歌词开关（悬浮窗歌词），默认关闭 */
    suspend fun desktopLyric(): Boolean =
        context.dataStore.data.map { p -> p[Keys.DESKTOP_LYRIC] ?: false }.first()

    /** 桌面歌词样式（字号/透明度/颜色/多行/暂停隐藏/锁定/位置） */
    suspend fun desktopLyricStyle(): String =
        context.dataStore.data.map { p -> p[Keys.DESKTOP_LYRIC_STYLE] ?: "" }.first()

    suspend fun setDesktopLyricStyle(json: String) {
        context.dataStore.edit { it[Keys.DESKTOP_LYRIC_STYLE] = json }
    }

    suspend fun setDesktopLyric(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DESKTOP_LYRIC] = enabled }
    }

    /** 切歌模式（对齐落雪 player.togglePlayMethod，默认列表循环） */
    suspend fun playMode(): String =
        context.dataStore.data.map { p ->
            p[Keys.PLAY_MODE]?.takeIf { it in com.yinyue.domain.play.PlayMode.entries.map { m -> m.name } }
                ?: com.yinyue.domain.play.PlayMode.DEFAULT.name
        }.first()

    suspend fun setPlayMode(mode: String) {
        context.dataStore.edit { it[Keys.PLAY_MODE] = mode }
    }

    /** B站限频间隔（毫秒）。合规下限 1500ms（1次/1.5秒）——只允许调更保守，不允许调更快 */
    suspend fun biliRateLimitMs(): Long =
        context.dataStore.data.map { p -> (p[Keys.BILI_RATE_LIMIT_MS] ?: 1_500L).coerceAtLeast(MIN_BILI_INTERVAL_MS) }.first()

    suspend fun setBiliRateLimitMs(ms: Long) {
        context.dataStore.edit { it[Keys.BILI_RATE_LIMIT_MS] = ms.coerceAtLeast(MIN_BILI_INTERVAL_MS) }
    }

    /** MV 画质偏好：AUTO（匿名 480P；有 Cookie 自动取高） / P480 / P720 / P1080 */
    suspend fun biliQuality(): String =
        context.dataStore.data.map { p -> p[Keys.BILI_QUALITY] ?: "AUTO" }.first()

    suspend fun setBiliQuality(q: String) {
        context.dataStore.edit { it[Keys.BILI_QUALITY] = q }
    }

    /** 用户自备 B站 Cookie（可选画质增强，非功能依赖）；空 = 匿名 */
    suspend fun biliCookie(): String =
        context.dataStore.data.map { p -> p[Keys.BILI_COOKIE] ?: "" }.first()

    suspend fun setBiliCookie(cookie: String) {
        context.dataStore.edit { it[Keys.BILI_COOKIE] = cookie.trim() }
    }

    suspend fun userAliases(): List<Pair<String, String>> =
        context.dataStore.data.map { p -> p[Keys.USER_ALIASES] ?: "" }.first()
            .lines().filter { it.contains("=") }
            .map { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

    suspend fun addUserAlias(alias: String, canonical: String) {
        context.dataStore.edit { p ->
            val old = p[Keys.USER_ALIASES] ?: ""
            p[Keys.USER_ALIASES] = (old.lines().filter { it.isNotBlank() } + "${alias.trim()}=${canonical.trim()}").joinToString("\n")
        }
    }

    companion object {
        /** 合规下限：B站全局 1 次/1.5 秒（v3.0 §5.5）。设置只能调慢，不能调快 */
        const val MIN_BILI_INTERVAL_MS = 1_500L

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
