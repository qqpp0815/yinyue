package com.yinyue.player.ui.mine

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.domain.play.PlaybackCoordinator
import com.yinyue.player.domain.play.PlaybackQueue
import com.yinyue.player.player.cache.MusicCache
import com.yinyue.player.ui.theme.YinYueColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 歌曲列表页的两种来源（我的页点「查看全部」进来） */
enum class SongListMode(val key: String, val title: String) {
    RECENT("recent", "最近播放"),
    FAVORITE("favorite", "收藏歌曲"),
    ;

    companion object {
        fun fromKey(key: String?): SongListMode = entries.firstOrNull { it.key == key } ?: RECENT
    }
}

/**
 * 独立歌曲列表页（最近播放 / 收藏）。
 *
 * 此前这两份列表直接平铺在「我的」页里：最近播放一多，音源管理/设置等入口被顶到屏幕外。
 * v0.9.3 起补齐**收藏的联动能力**（用户反馈"点进去只能看看，跟其他功能联动太少"）：
 * 播放全部 / 随机播放 / 整批加入播放列表 / 逐行取消收藏 / 加入播放列表，
 * 对齐网易云「我喜欢的音乐」的基本操作面。
 */
@Composable
fun SongListPage(
    mode: SongListMode,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit = {},
) {
    val vm: SongListViewModel = viewModel()
    val source = when (mode) {
        SongListMode.RECENT -> vm.recent
        SongListMode.FAVORITE -> vm.favorites
    }
    val songs by source.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val db = AppDatabase.get(appContext)

    fun toast(msg: String) = Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()

    /** 播放全部：以整份列表为播放上下文（这样"下一首/播完续播"才有地方去） */
    fun playAll(shuffle: Boolean) {
        val list = songs
        if (list.isEmpty()) return
        val ordered = if (shuffle) list.shuffled() else list
        PlaybackQueue.playList(
            ordered.map { PlaybackQueue.Item.Audio(it.id, it.songName, it.artistName) },
            0,
        )
        scope.launch { PlaybackCoordinator.get(appContext).play(ordered.first().id) }
        toast(if (shuffle) "随机播放 ${ordered.size} 首" else "播放全部 ${ordered.size} 首")
        onOpenPlayer()
    }

    /** 整批加入播放列表（与"试听列表/播放队列"联动） */
    fun addAllToQueue(list: List<MusicEntity>) {
        if (list.isEmpty()) return
        PlaybackQueue.addAll(list.map { PlaybackQueue.Item.Audio(it.id, it.songName, it.artistName) })
        toast("已加入播放列表 ${list.size} 首")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(4.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text(mode.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${songs.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (songs.isNotEmpty()) {
            // 顶部操作区：播放全部 / 随机播放 / 整批入队（收藏页的核心联动入口）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { playAll(shuffle = false) }) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放全部")
                }
                TextButton(onClick = { playAll(shuffle = true) }) {
                    Icon(Icons.Filled.Shuffle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("随机播放")
                }
                TextButton(onClick = { addAllToQueue(songs) }) {
                    Icon(Icons.Filled.PlaylistAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("加入播放列表")
                }
            }
        }

        if (songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (mode == SongListMode.RECENT) "还没有播放记录" else "还没有收藏歌曲（搜索/歌单里点心形即可收藏）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(songs, key = { "${mode.key}_${it.id}" }) { song ->
                SongRow(
                    song = song,
                    // 收藏页每行可直接取消收藏（与播放页心形、缓存保护同步）
                    onUnfavorite = if (mode == SongListMode.FAVORITE) {
                        {
                            scope.launch {
                                db.musicDao().setFavorite(song.id, false)
                                MusicCache.protect("fav:${song.id}", false)
                                toast("已取消收藏「${song.songName}」")
                            }
                        }
                    } else {
                        null
                    },
                    onAddToQueue = { addAllToQueue(listOf(song)) },
                ) {
                    // 以整份列表为播放上下文（而不是只播这一首）：
                    // 否则队列为空 → 上一首/下一首无处可去、并且"下一首预热(warmNext)"根本不会触发
                    val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                    PlaybackQueue.playList(
                        songs.map { PlaybackQueue.Item.Audio(it.id, it.songName, it.artistName) },
                        index,
                    )
                    scope.launch {
                        PlaybackCoordinator.get(appContext).play(song.id)
                    }
                    onOpenPlayer()
                }
            }
        }
    }
}

/** 单行歌曲（封面 + 歌名 + 歌手；右侧：入队 / 取消收藏 / 播放） */
@Composable
private fun SongRow(
    song: MusicEntity,
    onUnfavorite: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!song.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text("♪", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.songName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artistName.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onAddToQueue?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Filled.PlaylistAdd,
                    "加入播放列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onUnfavorite != null) {
            IconButton(onClick = onUnfavorite) {
                Icon(Icons.Filled.Favorite, "取消收藏", tint = YinYueColors.Red, modifier = Modifier.size(20.dp))
            }
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = YinYueColors.Red,
                modifier = Modifier.padding(horizontal = 10.dp).size(22.dp),
            )
        }
    }
}

class SongListViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val favorites: StateFlow<List<MusicEntity>> = db.musicDao().favorites()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _recent = MutableStateFlow<List<MusicEntity>>(emptyList())
    val recent: StateFlow<List<MusicEntity>> = _recent

    init {
        viewModelScope.launch { _recent.value = db.playRecordDao().recent(200) }
    }
}
