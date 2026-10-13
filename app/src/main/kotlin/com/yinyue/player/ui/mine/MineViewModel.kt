package com.yinyue.player.ui.mine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.MusicEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MineViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    /** 收藏走 DAO 的 Flow：收藏/取消收藏立即反映到界面 */
    val favorites: StateFlow<List<MusicEntity>> = db.musicDao().favorites()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 最近播放：只取预览需要的条数，全部记录在二级页里看 */
    private val _recent = MutableStateFlow<List<MusicEntity>>(emptyList())
    val recent: StateFlow<List<MusicEntity>> = _recent

    init {
        refresh()
    }

    /**
     * 重新读取最近播放。
     * 播放记录是"边听边写"的，而本 VM 在底部导航切换时会常驻，
     * 所以在「我的」页每次进入时刷新一次，否则列表会一直是上次的快照。
     */
    fun refresh() {
        viewModelScope.launch {
            _recent.value = runCatching { db.playRecordDao().recent(PREVIEW_FETCH) }.getOrDefault(emptyList())
        }
    }

    fun play(musicId: Long) {
        // 协调器自有作用域，直接启动
        com.yinyue.player.domain.play.PlaybackCoordinator.get(getApplication()).play(musicId)
    }

    private companion object {
        /** 预览列表取多少条（界面只显示前几首，多取一些是为了"查看全部"入口的计数准确） */
        const val PREVIEW_FETCH = 50
    }
}
