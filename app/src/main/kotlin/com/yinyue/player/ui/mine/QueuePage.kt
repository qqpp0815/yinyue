package com.yinyue.player.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinyue.player.domain.play.PlaybackCoordinator
import com.yinyue.player.domain.play.PlaybackQueue
import com.yinyue.player.ui.common.scrollToCurrentCenter
import com.yinyue.player.ui.theme.YinYueColors

/** 试听列表页（对齐落雪 default 列表）：点播即入，音频项与 MV 项混排 */
@Composable
fun QueuePage(onBack: () -> Unit) {
    val items by PlaybackQueue.items.collectAsState()
    val currentIndex by PlaybackQueue.currentIndex.collectAsState()
    val appContext = LocalContext.current.applicationContext
    // 进入页面/切歌时把当前歌滚到屏幕中间（用户要求：列表停在头部导致"看不到我点的歌"）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(currentIndex) {
        listState.scrollToCurrentCenter(currentIndex)
    }

    Scaffold(
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("试听列表", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${items.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { PlaybackQueue.clear() }) { Text("清空") }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            itemsIndexed(items) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            when (item) {
                                is PlaybackQueue.Item.Audio -> PlaybackCoordinator.get(appContext).play(item.musicId)
                                is PlaybackQueue.Item.Mv ->
                                    PlaybackCoordinator.get(appContext).playMv(item.bvid, item.title, item.artist)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (index == currentIndex) "▶" else (index + 1).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (index == currentIndex) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(30.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (index == currentIndex) YinYueColors.Red else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.artist.ifBlank { "未知歌手" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    // 类型徽标：MV / 音频
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(YinYueColors.TagBg)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            if (item is PlaybackQueue.Item.Mv) "MV" else "歌曲",
                            style = MaterialTheme.typography.labelSmall,
                            color = YinYueColors.Red,
                        )
                    }
                    IconButton(onClick = { PlaybackQueue.removeAt(index) }) {
                        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 0.5.dp)
            }
            if (items.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("试听列表为空，点播后自动加入", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
