package com.yinyue.player.ui.discover

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.Playlist
import com.yinyue.player.data.db.entity.PlaylistMusicRef
import com.yinyue.player.domain.play.PlaybackCoordinator
import com.yinyue.player.domain.play.PlaybackQueue
import com.yinyue.player.domain.search.SongResult
import kotlinx.coroutines.launch

/**
 * 榜单/歌单的歌曲集合页：整批播放、整批加入歌单、单曲播放/入队。
 * 曲目在加载时落库成实体（与搜索结果同构），因此后续切源、缓存、歌单逻辑完全复用。
 */
@Composable
fun CollectionSongsPage(
    platform: String,
    collectionId: String,
    isBoard: Boolean,
    title: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val vm: DiscoverViewModel = viewModel()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<SongResult>?>(null) }
    var pickFor by remember { mutableStateOf<List<SongResult>?>(null) }
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var newName by remember { mutableStateOf("") }

    fun toast(msg: String) = Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(collectionId) {
        songs = vm.loadCollectionTracks(platform, collectionId, isBoard).also {
            if (it.isEmpty()) toast("未取到曲目（该平台接口可能暂不可用）")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    songs?.let { "${it.size} 首 · $platform" } ?: "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    val list = songs ?: return@TextButton
                    if (list.isEmpty()) return@TextButton
                    pickFor = list
                    newName = ""
                    playlists = null
                    scope.launch { playlists = AppDatabase.get(appContext).playlistDao().allOnce() }
                },
            ) { Text("加入歌单") }
        }

        val list = songs
        if (list == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = com.yinyue.player.ui.theme.YinYueColors.Red)
            }
            return@Column
        }
        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无曲目", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // 整批播放（从第一首开始，后续入队）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Button(
                onClick = {
                    scope.launch {
                        PlaybackQueue.playNow(
                            PlaybackQueue.Item.Audio(
                                list.first().music.id,
                                list.first().music.songName,
                                list.first().music.artistName,
                            ),
                        )
                        PlaybackCoordinator.get(appContext).play(list.first().music.id)
                        list.drop(1).forEach {
                            PlaybackQueue.add(
                                PlaybackQueue.Item.Audio(it.music.id, it.music.songName, it.music.artistName),
                            )
                        }
                        onOpenPlayer()
                    }
                },
            ) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("播放全部")
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(list, key = { _, s -> "col_${s.music.id}" }) { index, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 以整个榜单/歌单为播放上下文，切歌按列表顺序走
                            PlaybackQueue.playList(
                                list.map { PlaybackQueue.Item.Audio(it.music.id, it.music.songName, it.music.artistName) },
                                index,
                            )
                            scope.launch {
                                PlaybackCoordinator.get(appContext).play(song.music.id)
                                onOpenPlayer()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (index + 1).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(26.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.music.songName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                song.music.artistName.takeIf { it.isNotBlank() },
                                song.music.albumName?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            val added = PlaybackQueue.add(
                                PlaybackQueue.Item.Audio(
                                    song.music.id, song.music.songName, song.music.artistName,
                                ),
                            )
                            toast(if (added) "已加入播放列表" else "已在播放列表中")
                        },
                    ) { Icon(Icons.Filled.Add, "加入播放列表", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(
                        onClick = {
                            pickFor = listOf(song)
                            newName = ""
                            playlists = null
                            scope.launch { playlists = AppDatabase.get(appContext).playlistDao().allOnce() }
                        },
                    ) { Icon(Icons.Filled.PlaylistAdd, "加入歌单", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(start = 42.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    thickness = 0.5.dp,
                )
            }
        }
    }

    // 加入歌单（批量）
    pickFor?.takeIf { it.isNotEmpty() }?.let { batch ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickFor = null },
            title = { Text("加入歌单") },
            text = {
                Column {
                    Text(
                        if (batch.size == 1) batch.first().music.songName else "${batch.size} 首歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (playlists == null) {
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        playlists!!.take(8).forEach { pl ->
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val dao = AppDatabase.get(appContext).playlistDao()
                                            var pos = dao.nextPosition(pl.id)
                                            batch.forEach { s ->
                                                dao.addSong(PlaylistMusicRef(pl.id, s.music.id, pos++))
                                            }
                                            toast("已加入「${pl.name}」（${batch.size} 首）")
                                        }
                                        pickFor = null
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("新建歌单名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim().ifBlank { "我的歌单" }
                        scope.launch {
                            val dao = AppDatabase.get(appContext).playlistDao()
                            val newId = dao.insert(Playlist(name = name, createdAt = System.currentTimeMillis()))
                            var pos = dao.nextPosition(newId)
                            batch.forEach { s -> dao.addSong(PlaylistMusicRef(newId, s.music.id, pos++)) }
                            toast("已创建并加入「$name」（${batch.size} 首）")
                        }
                        pickFor = null
                    },
                ) { Text(if (newName.isBlank()) "新建并加入" else "新建「${newName.trim()}」") }
            },
            dismissButton = { TextButton(onClick = { pickFor = null }) { Text("取消") } },
        )
    }
}

