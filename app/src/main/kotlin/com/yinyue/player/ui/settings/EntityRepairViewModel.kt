package com.yinyue.player.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.domain.normalize.EntityRepairTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RepairUiState(
    val entities: List<MusicEntity> = emptyList(),
    val selected: List<Long> = emptyList(),
    val message: String? = null,
    val loading: Boolean = true,
)

/** 实体合并/拆分工具（v3.0 §13 R7 对策） */
class EntityRepairViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    private val _state = MutableStateFlow(RepairUiState())
    val state: StateFlow<RepairUiState> = _state

    fun load() {
        viewModelScope.launch {
            val list = db.musicDao().recent(100)
            _state.value = _state.value.copy(entities = list, loading = false)
        }
    }

    fun toggleSelect(id: Long) {
        val cur = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (id in cur) cur - id else (cur + id).takeLast(2),
        )
    }

    /** 合并选中的两个实体（第一个为源，第二个为目标） */
    fun mergeSelected() {
        val sel = _state.value.selected
        if (sel.size != 2) {
            _state.value = _state.value.copy(message = "请选择两个实体（先选源，再选目标）")
            return
        }
        viewModelScope.launch {
            val r = EntityRepairTool.merge(db, fromId = sel[0], toId = sel[1])
            _state.value = _state.value.copy(
                message = if (r != null) "已合并：迁移 ${r.movedLinks} 个音源" else "合并失败",
                selected = emptyList(),
            )
            load()
        }
    }

    /** 拆分：把选中实体的全部链接拆到新的歌名/歌手下 */
    fun splitEntity(musicId: Long, newSongName: String, newArtistName: String) {
        viewModelScope.launch {
            val links = db.audioLinkDao().listByMusicOrdered(musicId).map { it.id }
            val newId = EntityRepairTool.split(
                db = db,
                context = getApplication(),
                musicId = musicId,
                linkIds = links,
                newSongName = newSongName,
                newArtistName = newArtistName,
            )
            _state.value = _state.value.copy(
                message = if (newId != null) "已拆分为新实体 #$newId" else "拆分失败",
                selected = emptyList(),
            )
            load()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
