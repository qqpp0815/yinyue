package com.yinyue.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.OriginalState
import com.yinyue.player.domain.play.PlaybackCoordinator
import com.yinyue.player.domain.play.PlaybackQueue
import com.yinyue.player.domain.search.MvResult
import com.yinyue.player.domain.search.SongResult
import com.yinyue.player.ui.theme.YinYueColors
import kotlinx.coroutines.launch

/**
 * 搜索结果页（对齐网易云搜索结果界面）：
 * 顶部：返回 + 胶囊搜索框；Tab：综合 / 单曲 / MV；
 * 综合 = MV 卡片（若有）+ 「从「关键词」听起」+ 歌曲列表；
 * 歌曲行 = 歌名 + 标签行（原唱/音质/数据）+ 歌手-专辑 + 右侧（收藏/添加/更多）。
 */
@Composable
fun SearchScreen(onOpenPlayer: () -> Unit) {
    val vm: SearchViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) } // 0 综合 / 1 单曲 / 2 MV
    var menuForSong by remember { mutableStateOf<SongResult?>(null) }
    var menuForMv by remember { mutableStateOf<MvResult?>(null) }
    var favIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showModeDialog by remember { mutableStateOf(false) }
    // 批量加入歌单：单曲=1 条，歌手结果=全部歌曲（null=未打开）
    var pickListForSongs by remember { mutableStateOf<List<SongResult>?>(null) }
    var pickLists by remember { mutableStateOf<List<com.yinyue.player.data.db.entity.Playlist>?>(null) }
    var pickNewName by remember { mutableStateOf("") }

    val page = state.page
    // 载入收藏状态（展示心形高亮）
    androidx.compose.runtime.LaunchedEffect(page) {
        if (page != null) {
            val db = com.yinyue.player.data.db.AppDatabase.get(appContext)
            favIds = page.songs.mapNotNull { s ->
                if (db.musicDao().findById(s.music.id)?.favorite == true) s.music.id else null
            }.toSet()
        }
    }
    val toggleFav: (SongResult) -> Unit = { song ->
        scope.launch {
            val db = com.yinyue.player.data.db.AppDatabase.get(appContext)
            val newFav = song.music.id !in favIds
            db.musicDao().setFavorite(song.music.id, newFav)
            com.yinyue.player.player.cache.MusicCache.protect("fav:${song.music.id}", newFav)
            favIds = if (newFav) favIds + song.music.id else favIds - song.music.id
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部：返回 + 胶囊搜索框 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { /* 搜索页为 Tab 根页：返回仅收起键盘 */ }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            // 用 BasicTextField 自绘胶囊：Material3 的 TextField 有固定的最小高度与内部 padding，
            // 强行压到 44dp 会把文字上下裁切（占位文字只剩中段的根因）
            BasicTextField(
                value = state.keyword,
                onValueChange = vm::onKeywordChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(YinYueColors.Red),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { vm.search() },
                ),
                modifier = Modifier.weight(1f).height(40.dp),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (state.keyword.isEmpty()) {
                                Text(
                                    "搜索歌曲 / MV",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                        if (state.keyword.isNotEmpty()) {
                            IconButton(
                                onClick = { vm.onKeywordChange("") },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    "清空",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
            // 搜索模式（F1：只搜歌 / 只搜MV / 都搜）——只搜歌时不会请求 B站，省时限频
            Text(
                when (state.mode) {
                    com.yinyue.player.domain.search.SearchMode.AUDIO_ONLY -> "只搜歌"
                    com.yinyue.player.domain.search.SearchMode.VIDEO_ONLY -> "只搜MV"
                    else -> "都搜"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showModeDialog = true }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Text(
                "搜索",
                style = MaterialTheme.typography.bodyMedium,
                color = YinYueColors.Red,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.search() }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }

        if (showModeDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showModeDialog = false },
                title = { Text("搜索范围") },
                text = {
                    Column {
                        listOf(
                            com.yinyue.player.domain.search.SearchMode.BOTH to "歌曲 + MV（默认）",
                            com.yinyue.player.domain.search.SearchMode.AUDIO_ONLY to "只搜歌（不请求 B站）",
                            com.yinyue.player.domain.search.SearchMode.VIDEO_ONLY to "只搜 MV（仅 B站）",
                        ).forEach { (m, label) ->
                            Text(
                                (if (state.mode == m) "● " else "○ ") + label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.onModeChange(m)
                                        showModeDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showModeDialog = false }) { Text("关闭") }
                },
            )
        }

        // ── Tab 栏（综合 / 单曲 / MV） ──
        if (page != null) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = YinYueColors.Red,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[tab]),
                        color = YinYueColors.Red,
                    )
                },
            ) {
                listOf("综合", "单曲 ${page.songs.size}", "MV ${page.mvResults.size}").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        }

        // 搜索历史 / 加载 / 失败标注
        if (page == null) {
            if (state.history.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                ) {
                    Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    // 清除入口（此前只能看不能删，落雪侧是单条删除 + 全部清空）
                    TextButton(onClick = { vm.clearHistory() }) { Text("清空", style = MaterialTheme.typography.bodySmall) }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.history) { h ->
                        AssistChip(
                            onClick = { vm.search(h) },
                            label = { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                            shape = RoundedCornerShape(16.dp),
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    "删除这条历史",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { vm.removeHistory(h) },
                                )
                            },
                        )
                    }
                }
            }
        }
        state.page?.failedPlatforms?.takeIf { it.isNotEmpty() }?.let { failed ->
            Text(
                "暂不可用：" + failed.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = YinYueColors.Red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (state.loading) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YinYueColors.Red)
            }
            return@Column
        }
        if (page == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("搜索你想听的歌", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        // ── 内容区 ──
        when (tab) {
            0 -> CompositeTab(
                keyword = page.keyword,
                artistName = page.artistName,
                songs = page.songs,
                mvResults = page.mvResults,
                favIds = favIds,
                appContext = appContext,
                onOpenPlayer = onOpenPlayer,
                onMenu = { menuForSong = it },
                onAdd = { addToQueue(it, appContext) },
                onToggleFav = toggleFav,
                onMvMenu = { menuForMv = it },
                onPlayAll = {
                    page.songs.firstOrNull()?.let {
                        com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext).play(it.music.id)
                        onOpenPlayer()
                    }
                },
                onAddAllToPlaylist = { all ->
                    pickListForSongs = all
                    pickNewName = ""
                    pickLists = null
                    scope.launch {
                        pickLists = com.yinyue.player.data.db.AppDatabase.get(appContext)
                            .playlistDao().allOnce()
                    }
                },
            )
            1 -> SongList(
                songs = page.songs,
                appContext = appContext,
                favIds = favIds,
                onOpenPlayer = onOpenPlayer,
                onMenu = { menuForSong = it },
                onAdd = { addToQueue(it, appContext) },
                onToggleFav = toggleFav,
            )
            else -> MvList(page.mvResults, appContext = appContext, onOpenPlayer = onOpenPlayer, onMenu = { menuForMv = it })
        }
    }

    // 歌曲操作菜单（对齐网易云右键菜单）：播放 / 下一首 / 加入列表 / 喜欢 / 下载 / 复制 / 音源
    menuForSong?.let { song ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuForSong = null },
            title = { Text(song.music.songName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuItem("播放") {
                        PlaybackQueue.playNow(
                            PlaybackQueue.Item.Audio(song.music.id, song.music.songName, song.music.artistName),
                        )
                        PlaybackCoordinator.get(appContext).play(song.music.id)
                        menuForSong = null
                        onOpenPlayer()
                    }
                    MenuItem("下一首播放") {
                        PlaybackQueue.addNext(
                            PlaybackQueue.Item.Audio(song.music.id, song.music.songName, song.music.artistName),
                        )
                        toast(appContext, "已设为下一首播放")
                        menuForSong = null
                    }
                    MenuItem("加入播放列表") {
                        addToQueue(song, appContext)
                        menuForSong = null
                    }
                    MenuItem("加入歌单…") {
                        menuForSong = null
                        pickListForSongs = listOf(song)
                        pickNewName = ""
                        pickLists = null
                        scope.launch {
                            pickLists = com.yinyue.player.data.db.AppDatabase.get(appContext).playlistDao().allOnce()
                        }
                    }
                    MenuItem(if (song.music.id in favIds) "取消喜欢" else "喜欢") {
                        toggleFav(song)
                        menuForSong = null
                    }
                    MenuItem("下载（缓存到本地）") {
                        menuForSong = null
                        scope.launch {
                            val r = com.yinyue.player.domain.play.DownloadManager.downloadMusic(appContext, song.music.id)
                            toast(appContext, r.message)
                        }
                    }
                    MenuItem("复制歌名") {
                        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("song", song.music.songName))
                        toast(appContext, "已复制歌名")
                        menuForSong = null
                    }
                    Text(
                        "可用音源（按优先级）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    song.audioLinks.take(6).forEach { link ->
                        Text(
                            "${link.platform} · ${qualityLabel(link.qualityLevel)} · " + when (link.cacheState) {
                                CacheState.LOCAL -> "本地"
                                CacheState.EXPIRED -> "已失效"
                                else -> "在线"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { menuForSong = null }) { Text("关闭") } },
        )
    }
    // 加入歌单选择器：列出已有歌单 + 新建（支持批量：歌手结果一键加入全部歌曲）
    pickListForSongs?.takeIf { it.isNotEmpty() }?.let { batch ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickListForSongs = null },
            title = { Text("加入歌单", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    when (val lists = pickLists) {
                        null -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            if (lists.isEmpty()) {
                                Text(
                                    "还没有歌单，在下方新建一个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            lists.take(8).forEach { pl ->
                                MenuItem(pl.name) {
                                    scope.launch {
                                        val db = com.yinyue.player.data.db.AppDatabase.get(appContext)
                                        val dao = db.playlistDao()
                                        var pos = dao.nextPosition(pl.id)
                                        batch.forEach { s ->
                                            dao.addSong(
                                                com.yinyue.player.data.db.entity.PlaylistMusicRef(pl.id, s.music.id, pos++),
                                            )
                                        }
                                        toast(appContext, "已加入「${pl.name}」（${batch.size} 首）")
                                    }
                                    pickListForSongs = null
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = pickNewName,
                                onValueChange = { pickNewName = it },
                                placeholder = { Text("新建歌单名") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val name = pickNewName.trim()
                        scope.launch {
                            val db = com.yinyue.player.data.db.AppDatabase.get(appContext)
                            val dao = db.playlistDao()
                            val newId = dao.insert(
                                com.yinyue.player.data.db.entity.Playlist(
                                    name = name.ifBlank { "我的歌单" },
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                            var pos = dao.nextPosition(newId)
                            batch.forEach { s ->
                                dao.addSong(
                                    com.yinyue.player.data.db.entity.PlaylistMusicRef(newId, s.music.id, pos++),
                                )
                            }
                            toast(appContext, "已创建并加入「${name.ifBlank { "我的歌单" }}」（${batch.size} 首）")
                        }
                        pickListForSongs = null
                    },
                ) { Text(if (pickNewName.isBlank()) "新建并加入" else "新建「${pickNewName.trim()}」") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pickListForSongs = null }) { Text("取消") }
            },
        )
    }

    // MV 操作菜单：播放 / 下一首 / 加入列表 / 下载（音轨+可选画面）/ 复制
    menuForMv?.let { mv ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuForMv = null },
            title = { Text("MV: ${mv.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuItem("播放MV") {
                        PlaybackQueue.playNow(PlaybackQueue.Item.Mv(mv.bvid, mv.title, mv.author))
                        PlaybackCoordinator.get(appContext).playMv(mv.bvid, mv.title, mv.author, mv.coverUrl)
                        menuForMv = null
                        onOpenPlayer()
                    }
                    MenuItem("下一首播放") {
                        PlaybackQueue.addNext(PlaybackQueue.Item.Mv(mv.bvid, mv.title, mv.author))
                        toast(appContext, "已设为下一首播放")
                        menuForMv = null
                    }
                    MenuItem("加入播放列表") {
                        val added = PlaybackQueue.add(PlaybackQueue.Item.Mv(mv.bvid, mv.title, mv.author))
                        toast(appContext, if (added) "已加入播放列表" else "已在播放列表中")
                        menuForMv = null
                    }
                    MenuItem("下载音轨（离线听）") {
                        menuForMv = null
                        scope.launch {
                            val r = com.yinyue.player.domain.play.DownloadManager.downloadMv(appContext, mv.bvid, mv.title, withVideo = false)
                            toast(appContext, r.message)
                        }
                    }
                    MenuItem("下载MV（含画面）") {
                        menuForMv = null
                        scope.launch {
                            val r = com.yinyue.player.domain.play.DownloadManager.downloadMv(appContext, mv.bvid, mv.title, withVideo = true)
                            toast(appContext, r.message)
                        }
                    }
                    MenuItem("复制标题") {
                        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("mv", mv.title))
                        toast(appContext, "已复制标题")
                        menuForMv = null
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { menuForMv = null }) { Text("关闭") } },
        )
    }
}
@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** 轻提示 */
private fun toast(context: android.content.Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/** 综合 Tab：MV 卡片 + 「从『关键词』听起」+ 歌曲列表（对齐网易云综合页） */
@Composable
private fun CompositeTab(
    keyword: String,
    artistName: String?,
    songs: List<SongResult>,
    mvResults: List<MvResult>,
    favIds: Set<Long>,
    appContext: android.content.Context,
    onOpenPlayer: () -> Unit,
    onMenu: (SongResult) -> Unit,
    onAdd: (SongResult) -> Unit,
    onToggleFav: (SongResult) -> Unit,
    onPlayAll: () -> Unit,
    onMvMenu: (MvResult) -> Unit = {},
    /** 歌手结果整批加入歌单（列表在本函数外，用回调交给页面处理） */
    onAddAllToPlaylist: (List<SongResult>) -> Unit = {},
) {
    LazyColumn(
        // 底部留白：避免最后一条被迷你播放条/导航栏遮挡
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 歌手卡片（歌手搜索时展示，对齐网易云）
        if (!artistName.isNullOrBlank()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 歌手头像占位（圆形，取歌手名首字）
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            artistName.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("歌手: $artistName", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "共 ${songs.size} 首（已去重）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 一键把该歌手去重后的全部歌曲加入歌单（用户要求：歌手结果要能整批收藏）
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onAddAllToPlaylist(songs) },
                        enabled = songs.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("加入歌单", style = MaterialTheme.typography.labelMedium) }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    thickness = 0.5.dp,
                )
            }
        }
        // MV 卡片（首条）
        mvResults.firstOrNull()?.let { mv ->
            item {
                MvRow(mv, appContext, onOpenPlayer, compact = false, onMenu = onMvMenu)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    thickness = 0.5.dp,
                )
            }
        }
        // 「从「关键词」听起」区块标题 + 播放全部
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (!artistName.isNullOrBlank()) "「$artistName」的全部歌曲" else "从「$keyword」听起",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(YinYueColors.Red)
                        .clickable(onClick = onPlayAll),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, "播放全部", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        // 歌曲列表（点播时以整列表为播放上下文，切歌按列表顺序走）
        val playListSongs = songs.take(60)
        itemsIndexed(playListSongs) { rowIndex, song ->
            SongRow(
                song = song,
                appContext = appContext,
                isFavorite = song.music.id in favIds,
                onOpenPlayer = onOpenPlayer,
                onMenu = { onMenu(song) },
                onAdd = { onAdd(song) },
                onToggleFav = { onToggleFav(song) },
                siblings = playListSongs,
                index = rowIndex,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                thickness = 0.5.dp,
            )
        }
    }
}

/** 网易云风格歌曲行：歌名（大字）+ 标签行（原唱/音质/数据）+ 歌手-专辑 + 右侧操作 */
@Composable
private fun SongRow(
    song: SongResult,
    appContext: android.content.Context,
    isFavorite: Boolean,
    onOpenPlayer: () -> Unit,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onToggleFav: () -> Unit,
    /** 所在列表与自身下标：点播时以整列表为播放上下文，切歌才能按列表顺序走 */
    siblings: List<SongResult> = emptyList(),
    index: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val queue = com.yinyue.player.domain.play.PlaybackQueue
                val list = siblings.ifEmpty { listOf(song) }
                queue.playList(
                    list.map {
                        com.yinyue.player.domain.play.PlaybackQueue.Item.Audio(
                            it.music.id, it.music.songName, it.music.artistName,
                        )
                    },
                    list.indexOfFirst { it.music.id == song.music.id }.coerceAtLeast(0),
                )
                com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext).play(song.music.id)
                onOpenPlayer()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // 歌名（大字）
                Text(
                    song.music.songName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // 标签行（原唱 / 音质 / 平台数 / MV 数）
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (song.music.verifiedOriginal == OriginalState.ORIGINAL ||
                        song.music.verifiedOriginal == OriginalState.USER_CONFIRMED
                    ) {
                        SmallTag("原唱", YinYueColors.Red, Color.White)
                    }
                    val bestQuality = song.audioLinks.maxOfOrNull { it.qualityLevel } ?: 1
                    if (bestQuality >= 4) {
                        SmallTag("超清母带", Color(0xFFB8860B), Color(0xFFFFF8E1))
                    } else if (bestQuality == 3) {
                        SmallTag("320K", YinYueColors.Red, YinYueColors.TagBg)
                    }
                    val platformCount = song.audioLinks.map { it.platform }.distinct().size
                    if (platformCount > 1) {
                        SmallTag("${platformCount}源", Color(0xFF8A8A8A), MaterialTheme.colorScheme.surfaceVariant)
                    }
                    val localCount = song.audioLinks.count { it.cacheState == CacheState.LOCAL }
                    if (localCount > 0) {
                        SmallTag("已缓存", Color(0xFF4CAF50), Color(0xFFE8F5E9))
                    }
                }
                Spacer(Modifier.height(2.dp))
                // 歌手 - 专辑
                Text(
                    listOfNotNull(
                        song.music.artistName.ifBlank { null },
                        song.music.albumName?.ifBlank { null },
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 右侧：收藏 / 添加 / 更多（网易云样式）
            IconButton(onClick = onToggleFav) {
                Icon(
                    Icons.Filled.FavoriteBorder,
                    "收藏",
                    tint = if (isFavorite) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, "加入播放列表", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 标签（网易云样式：小圆角，可自定义底色/字色） */
@Composable
private fun SmallTag(text: String, fg: Color, bg: Color) {
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
private fun SongList(
    songs: List<SongResult>,
    appContext: android.content.Context,
    favIds: Set<Long>,
    onOpenPlayer: () -> Unit,
    onMenu: (SongResult) -> Unit,
    onAdd: (SongResult) -> Unit,
    onToggleFav: (SongResult) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        // 单曲列表：同样以整列表为播放上下文
        itemsIndexed(songs) { rowIndex, song ->
            SongRow(
                song = song,
                appContext = appContext,
                isFavorite = song.music.id in favIds,
                onOpenPlayer = onOpenPlayer,
                onMenu = { onMenu(song) },
                onAdd = { onAdd(song) },
                onToggleFav = { onToggleFav(song) },
                siblings = songs,
                index = rowIndex,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                thickness = 0.5.dp,
            )
        }
    }
}

/** MV 行（对齐网易云 MV 卡片）：封面缩略图(带▶) + "MV:" 红字 + 标题 + 作者,时长,播放量 + 操作菜单 */
@Composable
private fun MvRow(
    mv: MvResult,
    appContext: android.content.Context,
    onOpenPlayer: () -> Unit,
    compact: Boolean,
    onMenu: ((MvResult) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                com.yinyue.player.domain.play.PlaybackQueue.playNow(
                    com.yinyue.player.domain.play.PlaybackQueue.Item.Mv(mv.bvid, mv.title, mv.author),
                )
                com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext).playMv(mv.bvid, mv.title, mv.author, mv.coverUrl)
                onOpenPlayer()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面缩略图 + 播放角标
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!mv.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = mv.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { err ->
                        android.util.Log.e(
                            "YY-DEBUG",
                            "cover load fail: ${err.result.throwable.message} url=${mv.coverUrl}",
                        )
                    },
                )
            } else {
                android.util.Log.w("YY-DEBUG", "cover url empty for ${mv.bvid} ${mv.title}")
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, "播放MV", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MV:", style = MaterialTheme.typography.bodyLarge, color = YinYueColors.Red, fontWeight = FontWeight.Medium)
                Text(
                    mv.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOfNotNull(
                    mv.author.ifBlank { null },
                    if (mv.durationSec > 0) fmtDuration(mv.durationSec) else null,
                    if (mv.play > 0) "播放:${"%.1f".format(mv.play / 10000.0)}万" else null,
                ).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, "播放MV", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (onMenu != null) {
            IconButton(onClick = { onMenu(mv) }) {
                Icon(Icons.Filled.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** MV 独立列表（MV Tab） */
@Composable
private fun MvList(
    mvResults: List<MvResult>,
    appContext: android.content.Context,
    onOpenPlayer: () -> Unit,
    onMenu: (MvResult) -> Unit = {},
) {
    if (mvResults.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有搜到相关 MV", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(mvResults, key = { it.bvid }) { mv ->
            MvRow(mv, appContext, onOpenPlayer, compact = true, onMenu = onMenu)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )
        }
    }
}

private fun addToQueue(song: SongResult, appContext: android.content.Context) {
    val added = PlaybackQueue.add(
        PlaybackQueue.Item.Audio(
            musicId = song.music.id,
            title = song.music.songName,
            artist = song.music.artistName,
        ),
    )
    toast(appContext, if (added) "已加入播放列表" else "已在播放列表中")
}

private fun qualityLabel(level: Int): String = when (level) {
    4 -> "无损"
    3 -> "320K"
    2 -> "128K"
    else -> "未知"
}

private fun fmtDuration(sec: Long): String = String.format("%02d:%02d", sec / 60, sec % 60)
