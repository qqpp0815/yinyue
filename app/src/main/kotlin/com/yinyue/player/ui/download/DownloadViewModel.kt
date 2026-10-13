package com.yinyue.player.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.cache.VideoStore
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.player.cache.MusicCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadedItem(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val group: String, // 音频缓存 / MV缓存 / 手动导入 / 抖音解析
    val fallbackName: String? = null,
)

data class DownloadUiState(
    val items: List<DownloadedItem> = emptyList(),
    val totalBytes: Long = 0,
    val loading: Boolean = true,
    val message: String? = null,
    val showRelayActions: Boolean = false,
)

/** 下载管理（对齐落雪"下载"页）：展示"找到即下载"产物，支持按项/按组删除 */
class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state

    fun refresh() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { scan() }
            _state.value = _state.value.copy(
                items = items,
                totalBytes = items.sumOf { it.sizeBytes },
                loading = false,
            )
        }
    }

    fun delete(item: DownloadedItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(item.path).delete() }
            _state.value = _state.value.copy(message = "已删除 ${item.name}", showRelayActions = false)
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VideoStore.clearAll(getApplication())
                File(getApplication<Application>().filesDir, "douyin_store").deleteRecursively()
                // 手动导入与 audio_store 保留（永久缓存策略），仅清 MV/抖音缓存
            }
            _state.value = _state.value.copy(message = "已清理 MV/抖音缓存（手动导入与收藏缓存保留）", showRelayActions = false)
            refresh()
        }
    }

    private suspend fun scan(): List<DownloadedItem> {
        val app = getApplication<Application>()
        val db = AppDatabase.get(app)
        val out = ArrayList<DownloadedItem>()
        /** 先落目录条目，再按数据库映射回「歌名 - 歌手」；查不到的才退回文件名。 */
        fun collect(root: File, group: String) {
            if (!root.exists()) return
            root.walkBottomUp().filter { it.isFile }.forEach { f ->
                if (f.name == "meta.txt") return@forEach // 元信息单独展示，不作为条目
                val meta = f.parentFile?.let { VideoStore.readMeta(app, it) }
                val display = if (meta.isNullOrBlank()) f.name else "$meta · ${f.name}"
                out.add(DownloadedItem(display, f.absolutePath, f.length(), group))
            }
        }
        collect(File(app.filesDir, "audio_downloads"), "已下载音频")
        enrichAudioDownloads(db, File(app.filesDir, "audio_downloads"), out)
        collect(File(app.filesDir, "video_store"), "MV 缓存")
        enrichMvDownloads(db, File(app.filesDir, "video_store"), out)
        collect(File(app.filesDir, "audio_store"), "音频缓存")
        collect(File(app.filesDir, "manual_store"), "手动导入")
        enrichManualDownloads(db, File(app.filesDir, "manual_store"), out)
        collect(File(app.filesDir, "douyin_store"), "抖音解析")
        enrichDouyinDownloads(db, File(app.filesDir, "douyin_store"), out)
        return out.sortedByDescending { it.sizeBytes }
    }

    private suspend fun enrichAudioDownloads(db: AppDatabase, root: File, out: MutableList<DownloadedItem>) {
        if (!root.exists()) return
        root.walkBottomUp().filter { it.isFile }.forEach { file ->
            val id = file.nameWithoutExtension.toLongOrNull() ?: return@forEach
            val music = db.musicDao().findById(id) ?: return@forEach
            out.replaceAll {
                if (it.path == file.absolutePath) {
                    it.copy(name = "${music.songName} - ${music.artistName}")
                } else {
                    it
                }
            }
        }
    }

    private suspend fun enrichMvDownloads(db: AppDatabase, root: File, out: MutableList<DownloadedItem>) {
        if (!root.exists()) return
        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val id = dir.name.toLongOrNull() ?: return@forEach
            val music = db.musicDao().findById(id) ?: return@forEach
            val display = "${music.songName} - ${music.artistName}"
            out.replaceAll { if (it.path.startsWith(dir.absolutePath + File.separator)) it.copy(name = display) else it }
        }
    }

    private suspend fun enrichManualDownloads(db: AppDatabase, root: File, out: MutableList<DownloadedItem>) {
        if (!root.exists()) return
        root.walkBottomUp().filter { it.isFile }.forEach { file ->
            val link = db.audioLinkDao().findByLocalPath(file.absolutePath) ?: return@forEach
            val music = db.musicDao().findById(link.musicId) ?: return@forEach
            out.replaceAll {
                if (it.path == file.absolutePath) {
                    it.copy(name = "${music.songName} - ${music.artistName}")
                } else {
                    it
                }
            }
        }
    }

    private suspend fun enrichDouyinDownloads(db: AppDatabase, root: File, out: MutableList<DownloadedItem>) {
        if (!root.exists()) return
        val mvLinks = db.mvLinkDao().allOnce()
        root.walkBottomUp().filter { it.isFile }.forEach { file ->
            val mv = mvLinks.firstOrNull {
                it.localAudioPath == file.absolutePath || it.localVideoPath == file.absolutePath
            } ?: return@forEach
            val music = db.musicDao().findById(mv.musicId) ?: return@forEach
            val display = "${music.songName} - ${music.artistName}"
            out.replaceAll {
                if (it.path == file.absolutePath) it.copy(name = display, fallbackName = mv.title) else it
            }
        }
    }
}
