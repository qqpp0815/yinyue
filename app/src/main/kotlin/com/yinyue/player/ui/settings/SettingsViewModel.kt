package com.yinyue.player.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.domain.normalize.AliasTable
import com.yinyue.domain.normalize.MetadataNormalizer
import com.yinyue.player.data.cache.VideoStore
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.OriginalState
import com.yinyue.player.data.prefs.SettingsRepository
import com.yinyue.player.player.cache.MusicCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val dedupMode: MetadataNormalizer.DedupMode = MetadataNormalizer.DedupMode.SONG_AND_ARTIST,
    val autoSwitch: Boolean = true,
    val autoSkip: Boolean = true,
    val audioQuotaGb: Float = 5f,
    val videoQuotaGb: Float = 10f,
    val cacheMvVideo: Boolean = false,
    val douyinRelay: String = "",
    val message: String? = null,
    val preferredQuality: String = com.yinyue.domain.match.QualityPreference.DEFAULT,
    val playMode: com.yinyue.domain.play.PlayMode = com.yinyue.domain.play.PlayMode.DEFAULT,
    val biliRateLimitSec: Float = 1.5f,
    val biliQuality: String = "AUTO",
    val biliCookieSet: Boolean = false,
    val pendingCount: Int = 0,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository.get(app)
    private val db = AppDatabase.get(app)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    private val _pendingList = MutableStateFlow<List<com.yinyue.player.data.db.entity.MusicEntity>>(emptyList())
    val pendingList: StateFlow<List<com.yinyue.player.data.db.entity.MusicEntity>> = _pendingList

    init {
        viewModelScope.launch {
            val dedup = settings.dedupMode()
            val auto = settings.autoSwitchSource()
            val autoSkip = settings.autoSkipOnError()
            val audio = settings.audioQuotaBytes()
            val video = settings.videoQuotaBytes()
            val cacheMv = settings.cacheMvVideo()
            val relay = settings.douyinRelay()
            val rate = settings.biliRateLimitMs()
            val quality = settings.biliQuality()
            val cookie = settings.biliCookie()
            val preferredQuality = settings.preferredQuality()
            val playMode = com.yinyue.domain.play.PlayMode.fromName(settings.playMode())
            _state.value = SettingsUiState(
                dedupMode = dedup,
                autoSwitch = auto,
                autoSkip = autoSkip,
                audioQuotaGb = audio / 1024f / 1024f / 1024f,
                videoQuotaGb = video / 1024f / 1024f / 1024f,
                cacheMvVideo = cacheMv,
                douyinRelay = relay,
                preferredQuality = preferredQuality,
                playMode = playMode,
                biliRateLimitSec = rate / 1000f,
                biliQuality = quality,
                biliCookieSet = cookie.isNotBlank(),
            )
            refreshPending()
        }
        MusicCache.audioQuotaBytes = runBlocking2 { settings.audioQuotaBytes() }
    }

    private fun runBlocking2(block: suspend () -> Long): Long = kotlinx.coroutines.runBlocking { block() }

    /** 桌面歌词（悬浮窗歌词）开关：记到设置里，App 启动时据此自动恢复显示 */
    fun setDesktopLyric(enabled: Boolean) {
        _state.value = _state.value.copy(
            message = if (enabled) "桌面歌词已开启（拖动移动，双击锁定/解锁）" else "桌面歌词已关闭",
        )
        viewModelScope.launch { settings.setDesktopLyric(enabled) }
    }

    /** 切歌模式（对齐落雪 player.togglePlayMethod） */
    fun setPlayMode(mode: com.yinyue.domain.play.PlayMode) {
        _state.value = _state.value.copy(
            playMode = mode,
            message = "切歌模式：${com.yinyue.domain.play.PlayModeLogic.label(mode)}",
        )
        viewModelScope.launch {
            // 协调器持有的是应用级单例；经 Holder 取（与播放页同一实例）
            com.yinyue.player.ui.player.PlaybackCoordinatorHolder.get().reloadPlayMode()
        }
    }

    /** 优先播放音质（对齐落雪 player.playQuality）：只向下兼容，不向上越档 */
    fun setPreferredQuality(quality: String) {
        _state.value = _state.value.copy(
            preferredQuality = quality,
            message = "优先音质已设为 ${com.yinyue.domain.match.QualityPreference.label(quality)}（必要时自动降级）",
        )
        viewModelScope.launch { settings.setPreferredQuality(quality) }
    }

    fun setDedupMode(mode: MetadataNormalizer.DedupMode) {
        _state.value = _state.value.copy(dedupMode = mode)
        viewModelScope.launch { settings.setDedupMode(mode) }
    }

    fun setAutoSwitch(enabled: Boolean) {
        _state.value = _state.value.copy(autoSwitch = enabled)
        viewModelScope.launch { settings.setAutoSwitchSource(enabled) }
    }

    /** 播放失败自动跳下一首（对齐落雪 autoSkipOnError；连续失败由 AutoSkipGuard 熔断） */
    fun setAutoSkip(enabled: Boolean) {
        _state.value = _state.value.copy(autoSkip = enabled)
        viewModelScope.launch { settings.setAutoSkipOnError(enabled) }
    }

    fun setAudioQuotaGb(gb: Float) {
        _state.value = _state.value.copy(audioQuotaGb = gb)
        viewModelScope.launch {
            settings.setAudioQuotaBytes((gb * 1024 * 1024 * 1024).toLong())
            // 必须走 updateQuota：淘汰器持有配额副本，只改变量的话滑杆拉到底也不会触发淘汰
            MusicCache.updateQuota((gb * 1024 * 1024 * 1024).toLong())
        }
    }

    fun setVideoQuotaGb(gb: Float) {
        _state.value = _state.value.copy(videoQuotaGb = gb)
        viewModelScope.launch {
            settings.setVideoQuotaBytes((gb * 1024 * 1024 * 1024).toLong())
            // 视频配额同理：改小后立刻按新配额淘汰，否则滑杆只是个显示
            com.yinyue.player.domain.play.VideoCacheJanitor.enforce(getApplication())
        }
    }

    fun setCacheMvVideo(enabled: Boolean) {
        _state.value = _state.value.copy(cacheMvVideo = enabled)
        viewModelScope.launch { settings.setCacheMvVideo(enabled) }
    }

    fun setDouyinRelay(url: String) {
        _state.value = _state.value.copy(douyinRelay = url)
        viewModelScope.launch { settings.setDouyinRelay(url) }
    }

    fun clearCaches() {
        viewModelScope.launch {
            MusicCache.clearAll(getApplication())
            VideoStore.clearAll(getApplication())
            _state.value = _state.value.copy(message = "双缓存已清空")
        }
    }

    fun refreshPending() {
        viewModelScope.launch {
            // 待定确认队列：verifiedOriginal == PENDING 的实体（多平台投票无法定论的那些）
            _pendingList.value = db.musicDao().pendingVerification()
            _state.value = _state.value.copy(pendingCount = _pendingList.value.size)
        }
    }

    /** 人工确认为原唱（权重最高，覆盖算法结论） */
    fun confirmOriginal(musicId: Long) {
        viewModelScope.launch {
            db.musicDao().updateVerified(musicId, OriginalState.USER_CONFIRMED, 1.0f)
            refreshPending()
        }
    }

    /** 人工标为翻唱（不再显示原唱标签） */
    fun markAsCover(musicId: Long) {
        viewModelScope.launch {
            db.musicDao().updateVerified(musicId, OriginalState.USER_MARKED_COVER, 0f)
            refreshPending()
        }
    }

    // ── 音源2（B站）设置：限频 / 画质 / 自备 Cookie ──

    fun setBiliRateLimitSec(sec: Float) {
        _state.value = _state.value.copy(biliRateLimitSec = sec)
        viewModelScope.launch {
            settings.setBiliRateLimitMs((sec * 1000).toLong())
            applyBiliRuntimeConfig()
        }
    }

    fun setBiliQuality(q: String) {
        _state.value = _state.value.copy(biliQuality = q)
        viewModelScope.launch {
            settings.setBiliQuality(q)
            applyBiliRuntimeConfig()
        }
    }

    fun setBiliCookie(cookie: String) {
        _state.value = _state.value.copy(
            biliCookieSet = cookie.isNotBlank(),
            message = if (cookie.isNotBlank()) "已保存登录 Cookie（仅本机，可选画质增强）" else "已清除 Cookie（恢复匿名 480P）",
        )
        viewModelScope.launch {
            settings.setBiliCookie(cookie)
            applyBiliRuntimeConfig()
        }
    }

    /** 设置改动后立即生效（限频下限由 SettingsRepository 兜住，不会低于合规值） */
    private suspend fun applyBiliRuntimeConfig() {
        val c = com.yinyue.player.mv.bilibili.BiliRateLimiter.Config
        c.intervalMs = settings.biliRateLimitMs()
        c.quality = settings.biliQuality()
        c.userCookie = settings.biliCookie().takeIf { it.isNotBlank() }
    }

    /** 用户补充别名（设置-验证组），立即生效于归一层 */
    fun addAlias(alias: String, canonical: String) {
        if (alias.isBlank() || canonical.isBlank()) return
        viewModelScope.launch {
            settings.addUserAlias(alias, canonical)
            // 立刻灌进归一层，否则要等下次启动才生效（文档要求"允许用户在设置中补充"即含立即生效）
            com.yinyue.player.domain.search.SearchDispatcher.get(getApplication()).refreshAliasTable()
            _state.value = _state.value.copy(message = "别名已添加：${alias.trim()} → ${canonical.trim()}（下次搜索生效）")
        }
    }
}
