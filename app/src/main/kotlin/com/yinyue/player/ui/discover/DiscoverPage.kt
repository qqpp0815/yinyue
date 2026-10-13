package com.yinyue.player.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yinyue.player.data.source.model.PlaylistBrief
import com.yinyue.player.data.source.model.TopListBoard
import com.yinyue.player.ui.theme.YinYueColors

/**
 * 发现页（重做）：分类筛选 + 头条大图推荐 + 双列歌单卡片 + 次要榜单横滑。
 *
 * 之前是一个 TabRow 加两条朴素列表，内容全糊在一起、层级不清。
 * 现在按「先看什么 → 再挑什么」组织：分类 chips → 头条 banner → 歌单卡片墙 → 榜单横滑（次要，可按需忽略）。
 */
@Composable
fun DiscoverPage(
    onBack: () -> Unit,
    onOpenCollection: (platform: String, id: String, isBoard: Boolean, title: String) -> Unit,
) {
    val vm: DiscoverViewModel = viewModel()
    val state by vm.state.collectAsState()
    // 状态驱动：分类或排序一变就重新拉取（点击只改状态，避免"点了没反应"这类指令式调用的坑）
    androidx.compose.runtime.LaunchedEffect(state.playlistOrder, state.playlistTag) {
        vm.loadPlaylists()
    }
    val hero = state.playlists.firstOrNull()
    val rest = if (hero != null) state.playlists.drop(1) else state.playlists

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("发现", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            // ── 分类筛选（平台支持才有；直接决定"推荐是不是你的口味"）──
            if (state.tags.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            TagChip("全部", state.playlistTag.isBlank()) { vm.selectTag("") }
                        }
                        items(state.tags) { tag ->
                            TagChip(tag, state.playlistTag == tag) { vm.selectTag(tag) }
                        }
                    }
                }
            }

            // 加载中：给出明确反馈（否则切分类时页面"看起来没反应"）
            if (state.loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = YinYueColors.Red,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // ── 推荐歌单标题 + 排序切换 ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("推荐歌单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    SortTab("最热", state.playlistOrder == "hot") { vm.selectOrder("hot") }
                    Spacer(Modifier.width(12.dp))
                    SortTab("最新", state.playlistOrder == "new") { vm.selectOrder("new") }
                }
            }

            // ── 头条 banner（第一张歌单大图，撑起页面气场）──
            if (hero != null) {
                item {
                    HeroBanner(hero) {
                        onOpenCollection(hero.platform, hero.playlistId, false, hero.name)
                    }
                }
            }

            // ── 歌单卡片墙（双列）──
            items(rest.chunked(2)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { pl ->
                        PlaylistCard(pl, Modifier.weight(1f)) {
                            onOpenCollection(pl.platform, pl.playlistId, false, pl.name)
                        }
                    }
                    // 奇数个时补一个空位，保证卡片宽度一致
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // ── 排行榜（次要区：横滑，不想看直接略过）──
            if (state.boards.isNotEmpty()) {
                item {
                    Text(
                        "排行榜",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.boards, key = { "${it.platform}:${it.boardId}" }) { board ->
                            BoardCard(board) {
                                onOpenCollection(board.platform, board.boardId, true, board.name)
                            }
                        }
                    }
                }
            }

            state.message?.let { msg ->
                item {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = YinYueColors.Red,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** 分类 chip（选中态用主色实心） */
@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) YinYueColors.Red else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SortTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(4.dp),
    )
}

/** 头条大图 banner：封面铺底 + 渐变遮罩 + 文案叠加 */
@Composable
private fun HeroBanner(pl: PlaylistBrief, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (!pl.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = pl.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // 底部渐变遮罩：保证任何封面上的文字都清晰
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.65f)),
                    ),
                ),
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("精选推荐", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Text(
                    pl.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${pl.platformLabel()} · ${fmtCount(pl.trackCount.toLong())}首 · 播放 ${fmtPlayCount(pl.playCount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(YinYueColors.Red),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, "打开歌单", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 歌单卡片（大封面 + 文案，双列） */
@Composable
private fun PlaylistCard(pl: PlaylistBrief, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!pl.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = pl.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // 播放量角标
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(11.dp))
                Text(
                    fmtPlayCount(pl.playCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Text(
            pl.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            pl.platformLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 榜单卡片（次要区横滑，尺寸小于歌单卡片） */
@Composable
private fun BoardCard(board: TopListBoard, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(YinYueColors.TagBg),
            contentAlignment = Alignment.Center,
        ) {
            Text("榜", color = YinYueColors.Red, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            board.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 平台短名（卡片角标用） */
private fun PlaylistBrief.platformLabel(): String = when (platform) {
    "wy" -> "网易云"
    "kw" -> "酷我"
    "kg" -> "酷狗"
    "tx" -> "QQ音乐"
    "mg" -> "咪咕"
    else -> platform
}

private fun fmtCount(n: Long): String = if (n <= 0) "多" else n.toString()

private fun fmtPlayCount(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}
