package com.yinyue.player.ui.playlist

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.player.data.db.entity.Playlist
import com.yinyue.player.ui.theme.YinYueColors
import kotlinx.coroutines.launch

/**
 * 歌单页（对齐网易云）：分组标题 + 歌单行（方形封面占位 + 名称 + 曲目数 + 右箭头）；
 * 详情页 = 头部（封面/名称/播放全部）+ 歌曲列表。
 */
@Composable
fun PlaylistScreen(onOpenPlayer: () -> Unit, onOpenDiscover: () -> Unit = {}) {
    val vm: PlaylistViewModel = viewModel()
    val playlists by vm.playlists.collectAsState()
    val ui by vm.ui.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // 重命名 / 删除歌单（v1.0 §2.1 歌单操作明确含"重命名"；此前 ViewModel 有 rename 但页面无入口）
    var renameTarget by remember { mutableStateOf<com.yinyue.player.data.db.entity.Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<com.yinyue.player.data.db.entity.Playlist?>(null) }
    var menuForSong by remember { mutableStateOf<com.yinyue.player.data.db.entity.MusicEntity?>(null) }

    Scaffold(
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                if (ui.current != null) {
                    IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
                Text(ui.current?.name ?: "歌单", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (ui.current == null) {
                    IconButton(onClick = { showCreate = true }) { Icon(Icons.Filled.Add, "新建歌单") }
                }
            }
        },
    ) { padding ->
        if (ui.current == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // 发现入口（对齐落雪的「排行榜 / 歌单」两块；平台榜单与推荐歌单）
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenDiscover)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.LibraryMusic, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("发现", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "排行榜 · 推荐歌单（最新/最热）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 82.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        thickness = 0.5.dp,
                    )
                }
                item {
                    Text(
                        "我创建的歌单（${playlists.size}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(playlists, key = { it.id }) { p ->
                    PlaylistRow(
                        p,
                        onClick = { vm.open(p) },
                        onRename = { renameTarget = p; renameText = p.name },
                        onDelete = { deleteTarget = p },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 82.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        thickness = 0.5.dp,
                    )
                }
                if (playlists.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            Text("还没有歌单，点右上角 + 新建", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            val pid = ui.current!!.id
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val appContext = LocalContext.current.applicationContext
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // 头部：封面 + 名称 + 操作（网易云样式）
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.LibraryMusic, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ui.current!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${ui.songs.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::playAll) {
                                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("播放全部")
                                }
                                TextButton(onClick = { vm.delete(pid) }) {
                                    Text("删除歌单", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 0.5.dp)
                }
                itemsIndexed(ui.songs, key = { _, row -> row.music.id }) { index, row ->
                    val song = row.music
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 以整个歌单为播放上下文：切歌（按钮/滑动/播完续播）按歌单顺序走
                                val queue = com.yinyue.player.domain.play.PlaybackQueue
                                queue.playList(
                                    ui.songs.map {
                                        com.yinyue.player.domain.play.PlaybackQueue.Item.Audio(
                                            it.music.id, it.music.songName, it.music.artistName,
                                        )
                                    },
                                    index,
                                )
                                scope.launch { com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext).play(song.id) }
                                onOpenPlayer()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (index + 1).toString().padStart(2, '0'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(30.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.songName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(song.artistName.ifBlank { null }, song.albumName?.ifBlank { null }).joinToString(" - "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 标记：本地缓存 / 原唱（v3.0 §11）
                        if (row.hasLocalCache) {
                            TagChip("缓存", Color(0xFF4CAF50), Color(0xFFE8F5E9))
                            Spacer(Modifier.width(4.dp))
                        }
                        if (row.isOriginal) {
                            TagChip("原唱", YinYueColors.Red, YinYueColors.TagBg)
                            Spacer(Modifier.width(4.dp))
                        }
                        TextButton(onClick = { vm.moveSong(pid, index, index - 1) }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = { vm.moveSong(pid, index, index + 1) }, enabled = index < ui.songs.size - 1) { Text("↓") }
                        IconButton(onClick = { menuForSong = song }) {
                            Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 46.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("歌单名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.create(newName)
                    newName = ""
                    showCreate = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    // 重命名歌单
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("歌单名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) vm.rename(target.id, renameText)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    // 删除歌单（二次确认）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除歌单") },
            text = { Text("「${target.name}」将被删除，歌单内的歌曲实体与音源记录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    // 歌曲行菜单（移出本歌单 / 加入其他歌单 / 删除歌曲）
    menuForSong?.let { song ->
        AlertDialog(
            onDismissRequest = { menuForSong = null },
            title = { Text(song.songName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuText("移出本歌单") {
                        vm.removeSong(ui.current!!.id, song.id)
                        menuForSong = null
                    }
                    playlists.filter { it.id != ui.current?.id }.forEach { other ->
                        MenuText("加入「${other.name}」") {
                            vm.addSongTo(other.id, song.id)
                            menuForSong = null
                        }
                    }
                    MenuText("删除歌曲（连音源记录）", danger = true) {
                        vm.deleteSong(song.id)
                        menuForSong = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuForSong = null }) { Text("关闭") } },
        )
    }
}

/** 小标签（缓存/原唱标记） */
@Composable
private fun TagChip(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun MenuText(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** 歌单行（网易云样式）：方形封面 + 名称 + 曲目数 + 右箭头 + ⋮（重命名/删除） */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.LibraryMusic, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "创建于 ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(playlist.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "歌单菜单") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(
                    text = { Text("删除歌单") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
