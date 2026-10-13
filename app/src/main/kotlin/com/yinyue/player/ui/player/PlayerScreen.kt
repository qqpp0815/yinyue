package com.yinyue.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.yinyue.domain.lyric.LrcParser
import com.yinyue.player.domain.play.PlaybackCoordinator
import com.yinyue.player.player.PlayerController
import com.yinyue.player.ui.common.scrollToCurrentCenter
import com.yinyue.player.ui.theme.YinYueColors
import kotlinx.coroutines.launch

/** 播放页（网易云风格）：渐变背景 + 黑胶旋转 + 歌词高亮滚动 + 底部控制栏 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun PlayerScreen(onBack: () -> Unit) {
    val playerState by PlayerController.state.collectAsState()
    val now by PlaybackCoordinatorHolder.nowPlaying.collectAsState()
    val coordinator = PlaybackCoordinatorHolder.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isFavorite by remember(now.musicId) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }

    if (showQueue) {
        QueueSheet(onDismiss = { showQueue = false })
    }
    if (showSources) {
        SourceSheet(musicId = now.musicId, onDismiss = { showSources = false })
    }

    LaunchedEffect(now.musicId) {
        if (now.musicId != 0L) {
            val db = com.yinyue.player.data.db.AppDatabase.get(context.applicationContext)
            isFavorite = db.musicDao().findById(now.musicId)?.favorite == true
        }
    }

    // 黑胶旋转（播放时转，暂停冻结）
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        YinYueColors.Red.copy(alpha = 0.30f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        // 左右滑动切歌：手势挂在下方"画面区"，**不再挂整屏** ——
        // 整屏挂横向拖动会和进度条的横向拖动抢手势（滑块拖不动/抖动），
        // 竖向拖动仍留给歌词列表，两者不冲突。
    var swipeAccum by remember { mutableFloatStateOf(0f) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.KeyboardArrowDown, "收起", modifier = Modifier.size(30.dp))
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        now.songName.ifBlank { "未在播放" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            now.artistName.ifBlank { null },
                            now.linksSummary.ifBlank { null },
                        ).joinToString(" · ").ifBlank { "音悦" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    // 链接状态徽标（本地缓存 / 在线 / 已失效 / 切源中，v3.0 §11）
                    val badge = when {
                        now.switching -> "切源中"
                        now.linkState.isNotBlank() -> now.linkState
                        else -> ""
                    }
                    if (badge.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when (badge) {
                                        "本地缓存" -> Color(0xFFE8F5E9)
                                        "已失效" -> MaterialTheme.colorScheme.surfaceVariant
                                        "切源中" -> YinYueColors.TagBg
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (badge) {
                                    "本地缓存" -> Color(0xFF4CAF50)
                                    "已失效" -> MaterialTheme.colorScheme.onSurfaceVariant
                                    "切源中" -> YinYueColors.Red
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.size(48.dp))
            }

            // 画面区：MV 模式渲染视频（占更大空间），听歌模式显示黑胶
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (now.mvMode) 0.58f else 0.42f)
                    .pointerInput(now.musicId) {
                        val swipeThreshold = 96.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { swipeAccum = 0f },
                            onDragEnd = {
                                if (swipeAccum <= -swipeThreshold) PlayerController.seekToNext()
                                else if (swipeAccum >= swipeThreshold) PlayerController.seekToPrevious()
                                swipeAccum = 0f
                            },
                            onDragCancel = { swipeAccum = 0f },
                            onHorizontalDrag = { _, dragAmount -> swipeAccum += dragAmount },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (now.mvMode) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                useController = false
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                PlayerController.bindToPlayerView(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    // 听歌模式：优先真实封面（圆形旋转），无封面回退黑胶
                    val cover = now.coverUrl
                    if (!cover.isNullOrBlank()) {
                        CircularCover(coverUrl = cover, rotationDegrees = { rotation.value })
                    } else {
                        BlackDisc(rotationDegrees = { rotation.value })
                    }
                }
            }

            // 进度拖动状态：只在拖动期间变化（不随播放位置刷新），因此提升到播放页也不会引起重组风暴
            val displayDuration = now.durationMs?.takeIf { it > 0 } ?: playerState.durationMs
            var dragging by remember { mutableStateOf(false) }
            var dragMs by remember { mutableFloatStateOf(0f) }

            // 歌词区（高亮滚动）+ 手势/按钮校准
            // 拖动时用拖动位置预览歌词，避免"手指在拖、歌词跟着播放位置跳"
            LyricView(
                lyricText = now.lyricText,
                dragPreviewMs = { if (dragging) dragMs.toLong() else null },
                userOffsetMs = now.lyricOffsetMs,
                onAdjustOffset = { coordinator.adjustLyricOffset(it) },
                modifier = Modifier.fillMaxWidth().weight(0.33f),
            )

            // 进度条（时长基准：平台元数据优先，ExoPlayer 读出的分片时长常不准）
            PlayerProgressBar(
                durationMs = displayDuration,
                dragging = { dragging },
                dragMs = { dragMs },
                onDragStart = { dragging = true },
                onDrag = { dragMs = it },
                onDragFinished = {
                    PlayerController.seekTo(dragMs.toLong())
                    dragging = false
                },
            )

            // 控制栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { PlayerController.seekToPrevious() }) {
                    Icon(Icons.Filled.SkipPrevious, "上一曲", modifier = Modifier.size(34.dp))
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { PlayerController.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "播放/暂停",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { PlayerController.seekToNext() }) {
                    Icon(Icons.Filled.SkipNext, "下一曲", modifier = Modifier.size(34.dp))
                }
                // 切歌模式（唯一按钮）：列表循环 → 单曲循环 → 随机播放 → 顺序播放，图标随模式变化
                IconButton(onClick = { coordinator.cyclePlayMode() }) {
                    val mode = coordinator.currentPlayMode()
                    val modeIcon = when (mode) {
                        com.yinyue.domain.play.PlayMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
                        com.yinyue.domain.play.PlayMode.RANDOM -> Icons.Filled.Shuffle
                        else -> Icons.Filled.Repeat
                    }
                    Icon(
                        modeIcon,
                        "切歌模式：${com.yinyue.domain.play.PlayModeLogic.label(mode)}",
                        tint = if (mode == com.yinyue.domain.play.PlayMode.LIST) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            YinYueColors.Red
                        },
                    )
                }
            }

            // 底部操作行：收藏 + 状态提示
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    scope.launch {
                        val db = com.yinyue.player.data.db.AppDatabase.get(context.applicationContext)
                        val music = db.musicDao().findById(now.musicId) ?: return@launch
                        val newFav = !music.favorite
                        db.musicDao().setFavorite(music.id, newFav)
                        com.yinyue.player.player.cache.MusicCache.protect("fav:${music.id}", newFav)
                        isFavorite = newFav
                    }
                }) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        "收藏",
                        tint = if (isFavorite) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 音源列表（v3.0 §11：按优先级排序、失效置灰、可手动切换）
                IconButton(onClick = { showSources = true }) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = "音源列表",
                        tint = if (now.switching) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 听歌 ⇄ 看MV（用户要求：与"播放列表"按钮互换位置 —— 切形态更常在拇指侧）
                IconButton(onClick = { coordinator.toggleMvMode() }) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = if (now.mvMode) "切换到听歌" else "看MV",
                        tint = if (now.mvMode) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 播放列表
                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        contentDescription = "播放列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (now.exhausted) {
                    Text(
                        now.exhaustedReason.ifBlank { "暂无可用音源" },
                        style = MaterialTheme.typography.bodySmall,
                        color = YinYueColors.Red,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else if (now.switching) {
                    Text("正在切换音源…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 圆形封面（网易云唱盘样式）：图片 + 外圈黑胶边，随播放旋转 */
@Composable
private fun CircularCover(coverUrl: String, rotationDegrees: () -> Float) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        val size = minOf(maxWidth, maxHeight).times(0.92f)
        Box(
            modifier = Modifier
                .size(size)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = rotationDegrees() }
                .clip(CircleShape)
                .background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                coil.compose.AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(size * 0.78f)
                        .clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
    }
}

/** 黑胶唱片：黑色圆盘 + 纹路 + 中心渐变圆；尺寸随容器自适应（避免溢出压住标题） */
@Composable
private fun BlackDisc(rotationDegrees: () -> Float) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        val discSize = minOf(maxWidth, maxHeight).times(0.92f)
        Box(
            modifier = Modifier
                .size(discSize)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = rotationDegrees() }
                .clip(CircleShape)
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(discSize * 0.85f).clip(CircleShape).background(Color(0xFF333333)))
            Box(modifier = Modifier.size(discSize * 0.65f).clip(CircleShape).background(Color(0xFF3C3C3C)))
            Box(
                modifier = Modifier
                    .size(discSize * 0.46f)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(discSize * 0.15f),
                )
            }
        }
    }
}

/**
 * 进度条：自己订阅播放位置。
 *
 * 位置每 200ms 变一次，若由播放页整屏订阅，黑胶动画/歌词/控制栏都会跟着重组，拖动就会掉帧；
 * 收进本组件后，重组范围只有这一条进度条。
 * 拖动期间用本地位置（1:1 跟手，不被轮询值顶回），只在松手时 seek 一次
 * （seek 会 flush 播放器，逐帧 seek 必然卡顿）。
 */
@Composable
private fun PlayerProgressBar(
    durationMs: Long,
    dragging: () -> Boolean,
    dragMs: () -> Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
) {
    val livePosition by PlayerController.position.collectAsState()
    val durationF = maxOf(1f, durationMs.toFloat())
    val draggingNow = dragging()
    val shown = if (draggingNow) dragMs() else livePosition.toFloat().coerceIn(0f, durationF)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown.coerceIn(0f, durationF),
            onValueChange = {
                if (!dragging()) onDragStart()
                onDrag(it)
            },
            onValueChangeFinished = onDragFinished,
            valueRange = 0f..durationF,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = YinYueColors.Red,
                activeTrackColor = YinYueColors.Red,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Text(fmt(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 歌词视图：当前行高亮并自动滚动居中；底部提供 ±0.5s 校准时移（MV 带片头时对齐用）。
 * 播放位置在本组件内部订阅（[dragPreviewMs] 非空 = 正在拖进度条，用拖动位置预览），
 * 这样播放页父级不必订阅位置，避免每秒多次整屏重组。
 */
@Composable
private fun LyricView(
    lyricText: String?,
    dragPreviewMs: () -> Long?,
    userOffsetMs: Long,
    onAdjustOffset: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val livePosition by PlayerController.position.collectAsState()
    val positionMs = dragPreviewMs() ?: livePosition
    if (lyricText.isNullOrBlank()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无歌词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val data = remember(lyricText) { LrcParser.parse(lyricText) }
    val lines = data.lines
    if (lines.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无歌词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val currentIndex = LrcParser.findCurrentLine(lines, positionMs + data.offsetMs + userOffsetMs)
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            item { Spacer(Modifier.height(60.dp)) }
            items(lines.size) { index ->
                val isCurrent = index == currentIndex
                Text(
                    lines[index].text.ifBlank { "·" },
                    style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 12.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
        // 歌词校准时移（右下角，小字提示当前偏移）
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (userOffsetMs != 0L) {
                Text(
                    "偏移 ${if (userOffsetMs > 0) "+" else ""}${userOffsetMs / 1000.0}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = YinYueColors.Red,
                )
            }
            androidx.compose.material3.TextButton(onClick = { onAdjustOffset(-500) }) { Text("−0.5s", style = MaterialTheme.typography.labelSmall) }
            androidx.compose.material3.TextButton(onClick = { onAdjustOffset(500) }) { Text("+0.5s", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * 音源列表弹层（v3.0 §11）：按优先级排序展示该歌曲的全部音源，
 * 失效置灰不可点；点击立即切换；长按/右侧按钮设为单首默认源。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(musicId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var links by remember { mutableStateOf<List<com.yinyue.player.data.db.entity.AudioLink>>(emptyList()) }
    var defaultKey by remember { mutableStateOf<String?>(null) }
    val now by PlaybackCoordinatorHolder.nowPlaying.collectAsState()

    androidx.compose.runtime.LaunchedEffect(musicId) {
        if (musicId != 0L) {
            val db = com.yinyue.player.data.db.AppDatabase.get(appContext)
            // 与实际尝试顺序一致：外置脚本优先级 > 手动 > 本地 > 在线 > 优先级分（SourceOrdering）
            val priorities = com.yinyue.player.script.ScriptManager.get(appContext).platformPriorities()
            links = com.yinyue.player.domain.play.SourceOrdering.ordered(
                links = db.audioLinkDao().listByMusicOrdered(musicId),
                platformPriority = priorities,
                localFileExists = { l -> l.localPath?.let { p -> java.io.File(p).exists() } == true },
            )
            defaultKey = db.musicDao().findById(musicId)?.defaultSourceId
        }
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("音源列表（按优先级）", style = MaterialTheme.typography.titleMedium)
            Text(
                "点击即切换；失效源可点（点了会强制重新取链）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn {
                itemsIndexed(links) { index, link ->
                    val expired = link.cacheState == com.yinyue.player.data.db.entity.CacheState.EXPIRED
                    // 按链接 id 判定"播放中"：只比平台会把同平台的多条链接全部标成播放中（用户实测 3 条 kw 全标）
                    val isCurrent = !now.switching && now.playingLinkId == link.id
                    // 该链接的歌手与实体歌手不一致 = 他人翻唱（默认不自动播放）
                    val isCover = link.artistKey.isNotBlank() &&
                        now.artistName.isNotBlank() &&
                        com.yinyue.domain.normalize.NameCleaner.coreKey(link.artistKey) !=
                        com.yinyue.domain.normalize.NameCleaner.coreKey(now.artistName)
                    val isDefault = defaultKey == "${link.platform}:${link.remoteTrackId}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 失效链接也能点：EXPIRED 只是一次失败的记录，不是永久判决
                            // （禁点会让用户没有任何手动重试的入口）
                            .clickable {
                                com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext).switchTo(link.id)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (index + 1).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    link.platform,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when (link.qualityLevel) {
                                        4 -> "无损"
                                        3 -> "320K"
                                        2 -> "128K"
                                        else -> "未知"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = YinYueColors.Red,
                                )
                                if (isCurrent) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("播放中", style = MaterialTheme.typography.labelSmall, color = YinYueColors.Red)
                                }
                                if (isDefault) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("默认源", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                                }
                                // 他人翻唱：自动播放不会优先用它（除非原唱都失败），点它才是主动选择
                                if (isCover) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "翻唱",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                when (link.cacheState) {
                                    com.yinyue.player.data.db.entity.CacheState.LOCAL -> "本地缓存 · 优先播放"
                                    com.yinyue.player.data.db.entity.CacheState.EXPIRED -> "已失效"
                                    else -> "在线"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!expired) {
                            androidx.compose.material3.TextButton(onClick = {
                                scope.launch {
                                    com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext)
                                        .setDefaultSource(musicId, link.id)
                                    defaultKey = "${link.platform}:${link.remoteTrackId}"
                                }
                            }) { Text(if (isDefault) "取消默认" else "设为默认", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 0.5.dp)
                }
                if (links.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (now.mvMode) "当前为 MV（无音源列表）" else "暂无音源（未导入脚本或未搜索到）",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 播放列表（队列）弹层：音频项与 MV 项混排，显示类型徽标；点条目切换播放 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val items by com.yinyue.player.domain.play.PlaybackQueue.items.collectAsState()
    val currentIndex by com.yinyue.player.domain.play.PlaybackQueue.currentIndex.collectAsState()
    val appContext = LocalContext.current.applicationContext
    // 打开弹层/切歌时把当前歌滚到屏幕中间（用户要求：列表停在头部导致"看不到我点的歌"）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(currentIndex) {
        listState.scrollToCurrentCenter(currentIndex)
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放列表（${items.size}）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "清空",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { com.yinyue.player.domain.play.PlaybackQueue.clear() }
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState) {
                itemsIndexed(items) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (item) {
                                    is com.yinyue.player.domain.play.PlaybackQueue.Item.Audio ->
                                        com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext).playQueueItemAt(index)
                                    is com.yinyue.player.domain.play.PlaybackQueue.Item.Mv ->
                                        com.yinyue.player.domain.play.PlaybackCoordinator.get(appContext)
                                            .playMv(item.bvid, item.title, item.artist)
                                }
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (index == currentIndex) "▶" else (index + 1).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (index == currentIndex) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(26.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (index == currentIndex) YinYueColors.Red else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (item is com.yinyue.player.domain.play.PlaybackQueue.Item.Mv) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(YinYueColors.TagBg)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text("MV", style = MaterialTheme.typography.labelSmall, color = YinYueColors.Red)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format("%02d:%02d", total / 60, total % 60)
}

/** 依赖桥（避免 Composable 直接取 Application） */
object PlaybackCoordinatorHolder {
    @Volatile
    var get: () -> PlaybackCoordinator = {
        error("PlaybackCoordinator not initialized")
    }

    val nowPlaying get() = get().nowPlaying
}





