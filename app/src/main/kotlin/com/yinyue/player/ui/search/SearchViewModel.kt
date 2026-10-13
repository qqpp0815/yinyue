package com.yinyue.player.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.domain.search.SearchDispatcher
import com.yinyue.player.domain.search.SearchMode
import com.yinyue.player.domain.search.SearchResultPage
import com.yinyue.player.domain.search.UnifiedSearchRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val keyword: String = "",
    val mode: SearchMode = SearchMode.BOTH,
    val loading: Boolean = false,
    val page: SearchResultPage? = null,
    val history: List<String> = emptyList(),
    val error: String? = null,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                history = AppDatabase.get(app).searchHistoryDao().recent(20).map { it.keyword },
            )
        }
    }

    fun onKeywordChange(v: String) {
        // 清空关键词即回到主页面（历史/引导页）：否则结果页一直留着，删掉歌手名也退不出去
        _state.value = if (v.isBlank()) {
            _state.value.copy(keyword = "", page = null, error = null)
        } else {
            _state.value.copy(keyword = v)
        }
    }

    /** 删除单条搜索历史 */
    fun removeHistory(keyword: String) {
        viewModelScope.launch {
            val dao = AppDatabase.get(getApplication()).searchHistoryDao()
            dao.delete(keyword)
            _state.value = _state.value.copy(history = dao.recent(20).map { it.keyword })
        }
    }

    /** 清空搜索历史 */
    fun clearHistory() {
        viewModelScope.launch {
            val dao = AppDatabase.get(getApplication()).searchHistoryDao()
            dao.clear()
            _state.value = _state.value.copy(history = emptyList())
        }
    }

    fun onModeChange(mode: SearchMode) {
        val old = _state.value.mode
        _state.value = _state.value.copy(mode = mode)
        val kw = _state.value.keyword
        if (kw.isNotBlank() && mode != old && _state.value.page != null) {
            search(kw, mode) // 模式切换重搜（二次搜索只补跑对应链路）
        }
    }

    fun search(keyword: String = _state.value.keyword, mode: SearchMode = _state.value.mode) {
        if (keyword.isBlank()) return
        _state.value = _state.value.copy(loading = true, keyword = keyword, error = null)
        viewModelScope.launch {
            try {
                val page = SearchDispatcher.get(getApplication()).search(UnifiedSearchRequest(keyword, mode))
                val history = AppDatabase.get(getApplication()).searchHistoryDao().recent(20).map { it.keyword }
                _state.value = _state.value.copy(loading = false, page = page, history = history)
                // 后台预热：按音源优先级先解析好可用源，用户点播即秒起（用户要求 6）
                com.yinyue.player.domain.play.SourcePreflight.warmUp(
                    getApplication(),
                    page.songs.map { it.music.id },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "搜索失败")
            }
        }
    }
}
