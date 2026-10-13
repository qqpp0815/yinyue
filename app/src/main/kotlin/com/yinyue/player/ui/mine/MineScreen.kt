package com.yinyue.player.ui.mine

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.domain.play.PlaybackCoordinator
import com.yinyue.player.ui.theme.YinYueColors
import kotlinx.coroutines.launch

/** 预览行数：超过部分进二级页看，避免长列表把功能入口顶出屏幕 */
private const val PREVIEW_ROWS = 6

/**
 * 我的页（Task 22）：功能入口常驻顶部 + 收藏/最近播放预览。
 *
 * 此前把「收藏全部 + 最近播放 50 条」直接平铺，音源管理/兜底导入/下载/设置被顶到很下面，
 * 想改设置得先滑过几十首歌（用户反馈"设计反人类"）。
 * 现在顺序是：功能入口 → 试听列表 → 收藏（预览）→ 最近播放（预览），长列表一律进二级页。
 */
@Composable
fun MineScreen(
    onOpenSourceManage: () -> Unit = {},
    onOpenFallback: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenSongList: (SongListMode) -> Unit = {},
) {
    val vm: MineViewModel = viewModel()
    val favorites by vm.favorites.collectAsState()
    val recent by vm.recent.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val queueItems by com.yinyue.player.domain.play.PlaybackQueue.items.collectAsState()

    // 每次进入本页刷新最近播放（播放记录是边听边写的，VM 常驻时列表会过期）
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text("我的", style = MaterialTheme.typography.titleLarge) }

        // ── 功能入口（常驻顶部，一眼可见、一步可达）──
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                EntryItem(Icons.Filled.Extension, "音源管理", onOpenSourceManage)
                EntryItem(Icons.Filled.Upload, "兜底导入", onOpenFallback)
                EntryItem(Icons.Filled.Download, "下载管理", onOpenDownloads)
                EntryItem(Icons.Filled.Settings, "设置", onOpenSettings)
            }
            HorizontalDivider()
        }

        // ── 试听列表（点播自动加入的队列）──
        item {
            ListItemRow(
                icon = Icons.Filled.QueueMusic,
                title = "试听列表",
                subtitle = "${queueItems.size} 首 · 点播自动加入",
                onClick = onOpenQueue,
            )
        }

        // ── 收藏（预览 + 查看全部）──
        item {
            SectionHeader("收藏歌曲", favorites.size) { onOpenSongList(SongListMode.FAVORITE) }
        }
        if (favorites.isEmpty()) {
            item { EmptyHint("还没有收藏歌曲（歌单/搜索里点心形即可收藏）") }
        } else {
            items(favorites.take(PREVIEW_ROWS), key = { "fav_${it.id}" }) { song ->
                PreviewSongRow(song) { playFromList(favorites, song, appContext, scope) }
            }
            if (favorites.size > PREVIEW_ROWS) {
                item { MoreRow(favorites.size) { onOpenSongList(SongListMode.FAVORITE) } }
            }
        }

        // ── 最近播放（预览 + 查看全部）──
        item {
            Spacer(Modifier.size(8.dp))
            SectionHeader("最近播放", recent.size) { onOpenSongList(SongListMode.RECENT) }
        }
        if (recent.isEmpty()) {
            item { EmptyHint("还没有播放记录") }
        } else {
            items(recent.take(PREVIEW_ROWS), key = { "recent_${it.id}" }) { song ->
                PreviewSongRow(song) { playFromList(recent, song, appContext, scope) }
            }
            if (recent.size > PREVIEW_ROWS) {
                item { MoreRow(recent.size) { onOpenSongList(SongListMode.RECENT) } }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

/** 功能入口（图标 + 文字，等宽四格） */
@Composable
private fun EntryItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(YinYueColors.TagBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = YinYueColors.Red, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** 带图标的功能/入口行 */
@Composable
private fun ListItemRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = YinYueColors.Red, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分组标题 + 右侧「查看全部」 */
@Composable
private fun SectionHeader(title: String, count: Int, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            "  $count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "查看全部",
            style = MaterialTheme.typography.bodySmall,
            color = YinYueColors.Red,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSeeAll).padding(6.dp),
        )
    }
}

/** 列表尾部「还有 N 首，查看全部」 */
@Composable
private fun MoreRow(total: Int, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSeeAll)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("查看全部 $total 首", style = MaterialTheme.typography.bodySmall, color = YinYueColors.Red)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * 从预览列表点歌：以整份列表为播放上下文（而不是只播这一首）。
 * 否则播放队列为空 → 上一首/下一首无处可去，且"下一首预热(warmNext)"不会触发（切歌就会变慢）。
 */
private fun playFromList(
    list: List<MusicEntity>,
    song: MusicEntity,
    appContext: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val index = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    com.yinyue.player.domain.play.PlaybackQueue.playList(
        list.map { com.yinyue.player.domain.play.PlaybackQueue.Item.Audio(it.id, it.songName, it.artistName) },
        index,
    )
    scope.launch { PlaybackCoordinator.get(appContext).play(song.id) }
}
/** 预览歌曲行（小封面 + 歌名/歌手，点击播放） */
@Composable
private fun PreviewSongRow(song: MusicEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
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
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.songName,
                style = MaterialTheme.typography.bodyMedium,
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
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "播放",
            tint = YinYueColors.Red,
            modifier = Modifier.size(20.dp),
        )
    }
}
