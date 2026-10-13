package com.yinyue.player.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.OriginalState
import com.yinyue.player.data.db.entity.Playlist
import com.yinyue.player.data.db.entity.PlaylistMusicRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 歌单歌曲行（含缓存/原唱标记，v3.0 §11 歌单页要求） */
data class PlaylistSongUi(
    val music: MusicEntity,
    val hasLocalCache: Boolean,
    val isOriginal: Boolean,
)

data class PlaylistUiState(
    val current: Playlist? = null,
    val songs: List<PlaylistSongUi> = emptyList(),
)

class PlaylistViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val playlists = db.playlistDao().all()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _ui = MutableStateFlow(PlaylistUiState())
    val ui: StateFlow<PlaylistUiState> = _ui

    fun open(playlist: Playlist) {
        viewModelScope.launch {
            val songs = db.playlistDao().songsOf(playlist.id)
            val rows = songs.map { music ->
                val links = db.audioLinkDao().listValidByMusic(music.id)
                PlaylistSongUi(
                    music = music,
                    hasLocalCache = links.any { it.cacheState == CacheState.LOCAL } ||
                        links.any { !it.localPath.isNullOrBlank() },
                    isOriginal = music.verifiedOriginal == OriginalState.ORIGINAL ||
                        music.verifiedOriginal == OriginalState.USER_CONFIRMED,
                )
            }
            _ui.value = PlaylistUiState(playlist, rows)
            // 打开歌单即后台预热：按音源优先级先把前几首歌的可用链接解析好并写回默认源，
            // 用户点歌/切歌时命中组合缓存 → 秒起播（不再每次点歌现场取链排序）
            com.yinyue.player.domain.play.SourcePreflight.warmUp(getApplication(), songs.map { it.id })
        }
    }

    fun close() {
        _ui.value = PlaylistUiState()
    }

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { db.playlistDao().insert(Playlist(name = name.trim(), createdAt = System.currentTimeMillis())) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { db.playlistDao().rename(id, name.trim()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            db.playlistDao().delete(id)
            if (_ui.value.current?.id == id) close()
        }
    }

    fun removeSong(playlistId: Long, musicId: Long) {
        viewModelScope.launch {
            db.playlistDao().removeSong(playlistId, musicId)
            open(_ui.value.current ?: return@launch)
        }
    }

    /** 删除歌曲（连音源记录：实体级级联清理两类链接 + 缓存） */
    fun deleteSong(musicId: Long) {
        viewModelScope.launch {
            com.yinyue.player.data.cache.VideoStore.clearEntity(getApplication(), musicId)
            db.musicDao().deleteByIds(listOf(musicId))
            open(_ui.value.current ?: return@launch)
        }
    }

    fun moveSong(playlistId: Long, from: Int, to: Int) {
        viewModelScope.launch {
            val refs = db.playlistDao().refsOf(playlistId).toMutableList()
            if (from !in refs.indices || to !in refs.indices) return@launch
            val item = refs.removeAt(from)
            refs.add(to, item)
            db.playlistDao().updateRefs(refs.mapIndexed { i, r -> r.copy(position = i) })
            open(_ui.value.current ?: return@launch)
        }
    }

    fun addSongTo(playlistId: Long, musicId: Long) {
        viewModelScope.launch {
            val pos = db.playlistDao().nextPosition(playlistId)
            db.playlistDao().addSong(PlaylistMusicRef(playlistId, musicId, pos))
            // A+：加入歌单后系统自动后台，为该歌曲自适应切源并锁定最优源；
            // 下次播放命中锁定直链 → 秒起播（失效会自动重解析）
            com.yinyue.player.domain.play.SourcePreflight.warmUp(getApplication(), listOf(musicId))
        }
    }

    /** 播放全部：从歌单第一首开始（协调器自有作用域驱动） */
    fun playAll() {
        val songs = _ui.value.songs
        val coordinator = com.yinyue.player.domain.play.PlaybackCoordinator.get(getApplication())
        if (songs.isEmpty()) return
        // 以整个歌单为播放上下文：之后"下一首/上一首/播完续播"按歌单顺序走
        com.yinyue.player.domain.play.PlaybackQueue.playList(
            songs.map {
                com.yinyue.player.domain.play.PlaybackQueue.Item.Audio(
                    it.music.id, it.music.songName, it.music.artistName,
                )
            },
            0,
        )
        coordinator.play(songs.first().music.id)
    }
}
