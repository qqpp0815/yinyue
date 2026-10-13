package com.yinyue.player.ui.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.source.SourceRegistry
import com.yinyue.player.data.source.model.PlaylistBrief
import com.yinyue.player.data.source.model.TopListBoard
import com.yinyue.player.domain.search.SearchDispatcher
import com.yinyue.player.domain.search.SongResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val loading: Boolean = false,
    val boards: List<TopListBoard> = emptyList(),
    val playlists: List<PlaylistBrief> = emptyList(),
    /** 推荐歌单排序：hot=最热 / new=最新 */
    val playlistOrder: String = "hot",
    /** 当前分类（空 = 全部） */
    val playlistTag: String = "",
    /** 各平台支持的分类并集（空 = 界面不显示分类筛选） */
    val tags: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * 排行榜与推荐歌单。
 *
 * 数据来自各平台 provider：网易云（分类选曲风）+ 酷我（中文歌单）+ 酷狗（中文榜单）等，
 * 只要平台实现了对应能力就会自动聚合进来，新增平台无需改这里。
 */
class DiscoverViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    init {
        loadBoards()
        loadTags()
        // 推荐歌单不在这里拉：由界面用 LaunchedEffect(state.order, state.tag) 驱动，
        // 保证"点分类/点排序"一定触发重新拉取（此前是指令式调用，出过一次点了没反应的反馈）
    }

    private fun providers() = SourceRegistry.all()

    fun loadBoards() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val boards = ArrayList<TopListBoard>()
            providers().forEach { p -> runCatching { boards.addAll(p.topLists()) } }
            _state.value = _state.value.copy(
                loading = false,
                boards = boards,
                message = if (boards.isEmpty() && _state.value.playlists.isEmpty()) {
                    "当前没有平台提供榜单（酷狗/网易云已支持）"
                } else {
                    null
                },
            )
        }
    }

    /** 汇总各平台支持的歌单分类（取并集，保持平台声明顺序） */
    fun loadTags() {
        viewModelScope.launch {
            val tags = LinkedHashSet<String>()
            providers().forEach { p -> runCatching { tags.addAll(p.playlistTags()) } }
            _state.value = _state.value.copy(tags = tags.toList())
        }
    }

    /** 只改状态：真正的拉取由界面的 LaunchedEffect 监听本状态变化后触发 */
    fun selectTag(tag: String) {
        _state.value = _state.value.copy(playlistTag = tag)
    }

    fun selectOrder(order: String) {
        _state.value = _state.value.copy(playlistOrder = order)
    }

    /** 按当前「排序 + 分类」拉取推荐歌单 */
    fun loadPlaylists() {
        val order = _state.value.playlistOrder
        val tag = _state.value.playlistTag
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            val lists = ArrayList<PlaylistBrief>()
            providers().forEach { p ->
                val tags = runCatching { p.playlistTags() }.getOrDefault(emptyList())
                // 选了分类时，只查"确实支持该分类"的平台。
                // 否则不支持分类的平台（酷我的推荐接口不认 cat）会把不受分类影响的结果混在最前面
                // —— 用户点了「华语」却发现头条和前排卡片一模一样，看起来就是"按了没反应"（实测复现）。
                if (tag.isNotBlank() && (tags.isEmpty() || tag !in tags)) return@forEach
                runCatching { lists.addAll(p.recommendPlaylists(order, offset = 0, limit = 30, cat = tag)) }
            }
            _state.value = _state.value.copy(
                loading = false,
                playlists = lists,
                message = if (lists.isEmpty()) {
                    "该分类没取到歌单（平台可能限流），换个分类或排序再试"
                } else {
                    null
                },
            )
        }
    }

    /** 榜单/歌单歌曲 → 落库成可播放结果（保序） */
    suspend fun loadCollectionTracks(
        platform: String,
        id: String,
        isBoard: Boolean,
        order: String = "",
    ): List<SongResult> {
        val provider = SourceRegistry.get(platform) ?: return emptyList()
        val tracks = runCatching {
            if (isBoard) provider.topListSongs(id) else provider.playlistSongs(id)
        }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return emptyList()
        return SearchDispatcher.get(getApplication()).materialize(tracks)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
