package com.yinyue.player.ui.fallback

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.fallback.FallbackImporter
import com.yinyue.player.player.cache.MusicCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 待确认的导入表单（v3.0 §9.1：文件名解析 + 可编辑元数据表单） */
data class ImportForm(
    val uri: Uri,
    val fileName: String,
    val songName: String,
    val artistName: String,
    val albumName: String = "",
)

data class FallbackUiState(
    val message: String? = null,
    val busy: Boolean = false,
    val lastImportedId: Long? = null,
    val showRelayActions: Boolean = false,
    /** 非空时展示可编辑确认表单 */
    val form: ImportForm? = null,
)

class FallbackViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(FallbackUiState())
    val state: StateFlow<FallbackUiState> = _state

    /** 第一步：选文件 → 解析文件名 → 弹出可编辑表单（不直接导入） */
    fun prepareFile(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { queryDisplayName(uri) } ?: "未知文件"
            val (artist, song) = FallbackImporter.parseFileName(name)
            _state.value = _state.value.copy(
                form = ImportForm(
                    uri = uri,
                    fileName = name,
                    songName = song,
                    artistName = artist,
                ),
            )
        }
    }

    fun updateForm(form: ImportForm) {
        _state.value = _state.value.copy(form = form)
    }

    fun cancelForm() {
        _state.value = _state.value.copy(form = null)
    }

    /** 第二步：按用户确认的元数据导入（拷贝 + 归一匹配 + 建实体/Link） */
    fun confirmImport() {
        val form = _state.value.form ?: return
        if (form.songName.isBlank()) {
            _state.value = _state.value.copy(message = "歌名不能为空")
            return
        }
        _state.value = _state.value.copy(busy = true, form = null, message = "导入中…")
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val id = withContext(Dispatchers.IO) { importWithMeta(app, form) }
                _state.value = _state.value.copy(busy = false, message = "已导入「${form.songName}」", lastImportedId = id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = "导入失败：${e.message}")
            }
        }
    }

    private suspend fun importWithMeta(app: Application, form: ImportForm): Long {
        val db = AppDatabase.get(app)
        // 拷贝文件到 manual_store（永久缓存）
        val dir = File(app.filesDir, "manual_store").apply { mkdirs() }
        val target = File(dir, form.fileName.substringAfterLast('/'))
        app.contentResolver.openInputStream(form.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取文件")

        val display = com.yinyue.domain.normalize.NameCleaner.coreSongName(form.songName)
        val settings = com.yinyue.player.data.prefs.SettingsRepository.get(app)
        val mode = settings.dedupMode()
        val key = when (mode) {
            com.yinyue.domain.normalize.MetadataNormalizer.DedupMode.SONG_ONLY ->
                com.yinyue.domain.normalize.NameCleaner.coreKey(display)
            else ->
                com.yinyue.domain.normalize.NameCleaner.coreKey(display) + "::" +
                    com.yinyue.domain.normalize.MetadataNormalizer.normalizeArtist(form.artistName)
        }
        val existing = db.musicDao().findByNormalizedKey(key)
        val musicId = existing?.id ?: db.musicDao().insert(
            MusicEntity(
                songName = display,
                artistName = form.artistName,
                albumName = form.albumName.ifBlank { null },
                normalizedKey = key,
                normalizedSong = com.yinyue.domain.normalize.MetadataNormalizer.normalizeSong(display),
                normalizedArtist = com.yinyue.domain.normalize.MetadataNormalizer.normalizeArtist(form.artistName),
                createdAt = System.currentTimeMillis(),
            ),
        )
        db.audioLinkDao().insert(
            AudioLink(
                musicId = musicId,
                scriptId = "-",
                platform = "local",
                remoteTrackId = target.name,
                qualityLevel = 1,
                originTag = com.yinyue.domain.match.PriorityScorer.UNKNOWN_ORIGIN,
                priorityScore = com.yinyue.domain.match.PriorityScorer.score(1, -1, "local"),
                localPath = target.absolutePath,
                cacheState = CacheState.LOCAL,
                lastVerified = System.currentTimeMillis(),
            ),
        )
        MusicCache.protect("manual:$musicId:${target.name}", true) // 永久缓存不淘汰
        return musicId
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    fun importDouyin(shareUrl: String, withVideo: Boolean = false) {
        if (_state.value.busy) return
        if (shareUrl.isBlank()) {
            _state.value = _state.value.copy(message = "请粘贴抖音分享链接", showRelayActions = false)
            return
        }
        _state.value = _state.value.copy(busy = true, message = "解析中（尽力而为，可能失败）…", showRelayActions = false)
        viewModelScope.launch {
            try {
                val r = FallbackImporter.importDouyinShare(getApplication(), shareUrl.trim(), withVideo)
                _state.value = _state.value.copy(
                    busy = false,
                    message = "解析成功「${r.songName}」，音频${if (withVideo) "+画面" else ""}已落盘",
                    lastImportedId = r.musicId,
                    showRelayActions = false,
                )
            } catch (e: Exception) {
                // 解析/下载的失败信息已按阶段组织（短链跳转/解析/下载），直接展示给用户
                _state.value = _state.value.copy(busy = false, message = e.message ?: "抖音解析失败", showRelayActions = (e.message ?: "").contains("中继"))
            }
        }
    }
}
