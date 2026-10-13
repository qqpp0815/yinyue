package com.yinyue.player.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yinyue.player.player.PlayerController
import com.yinyue.player.ui.fallback.FallbackImportPage
import com.yinyue.player.ui.mine.MineScreen
import com.yinyue.player.ui.player.PlayerScreen
import com.yinyue.player.ui.playlist.PlaylistScreen
import com.yinyue.player.ui.search.SearchScreen
import com.yinyue.player.ui.settings.SettingsPage
import com.yinyue.player.ui.source.SourceManagePage
import com.yinyue.player.ui.theme.YinYueColors

/** 下载管理等非导航页可请求的极简跨页跳转信号 */
object AppRootNavigator : Navigator {
    private val state = mutableStateOf<String?>(null)

    val pendingAction: String? get() = state.value

    private fun consumeAction(): String? {
        val action = pendingAction
        state.value = null
        return action
    }

    override fun openSettings() {
        state.value = "settings"
    }

    override fun openFallbackImport() {
        state.value = "fallback"
    }

    internal fun consumePendingAction(): String? = consumeAction()
}

/** 底部导航的三个 Tab（已确认决策：搜索/歌单/我的，播放页为独立页） */
private data class TabItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val TABS = listOf(
    TabItem("search", "搜索", Icons.Filled.Search),
    TabItem("playlist", "歌单", Icons.Filled.LibraryMusic),
    TabItem("mine", "我的", Icons.Filled.Person),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    LaunchedEffect(AppRootNavigator.pendingAction) {
        when (val action = AppRootNavigator.consumePendingAction()) {
            "settings" -> navController.navigate("settings") { launchSingleTop = true }
            "fallback" -> navController.navigate("fallback") { launchSingleTop = true }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TABS.map { it.route }

    // 用 Column + weight(1f) 强制线性布局：内容区严格避开底部播放条/导航栏（避免 Scaffold insets 的遮挡问题）
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = "search",
                modifier = Modifier.fillMaxSize(),
            ) {
                composable("search") { SearchScreen(onOpenPlayer = { navController.navigate("player") }) }
                composable("playlist") {
                    PlaylistScreen(
                        onOpenPlayer = { navController.navigate("player") },
                        onOpenDiscover = { navController.navigate("discover") },
                    )
                }
                composable("discover") {
                    com.yinyue.player.ui.discover.DiscoverPage(
                        onBack = { navController.popBackStack() },
                        onOpenCollection = { platform, id, isBoard, title ->
                            navController.navigate("collection/$platform/$id/$isBoard/" + java.net.URLEncoder.encode(title, "UTF-8"))
                        },
                    )
                }
                composable("collection/{platform}/{id}/{isBoard}/{title}") { entry ->
                    com.yinyue.player.ui.discover.CollectionSongsPage(
                        platform = entry.arguments?.getString("platform") ?: "wy",
                        collectionId = entry.arguments?.getString("id") ?: "",
                        isBoard = entry.arguments?.getString("isBoard") == "true",
                        title = java.net.URLDecoder.decode(entry.arguments?.getString("title") ?: "", "UTF-8"),
                        onBack = { navController.popBackStack() },
                        onOpenPlayer = { navController.navigate("player") },
                    )
                }
                composable("mine") {
                    MineScreen(
                        onOpenSourceManage = { navController.navigate("sourceManage") },
                        onOpenFallback = { navController.navigate("fallback") },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenDownloads = { navController.navigate("downloads") },
                        onOpenQueue = { navController.navigate("queue") },
                        onOpenSongList = { mode -> navController.navigate("songlist/${mode.key}") },
                    )
                }
                composable("queue") { com.yinyue.player.ui.mine.QueuePage(onBack = { navController.popBackStack() }) }
                // 最近播放 / 收藏的完整列表（「我的」页只放预览，长列表进这里看）
                composable("songlist/{mode}") { entry ->
                    com.yinyue.player.ui.mine.SongListPage(
                        mode = com.yinyue.player.ui.mine.SongListMode.fromKey(entry.arguments?.getString("mode")),
                        onBack = { navController.popBackStack() },
                        onOpenPlayer = { navController.navigate("player") },
                    )
                }
                composable("sourceManage") { SourceManagePage(onBack = { navController.popBackStack() }) }
                composable("fallback") {
                    FallbackImportPage(
                        onBack = { navController.popBackStack() },
                        navigator = AppRootNavigator,
                    )
                }
                composable("settings") {
                    SettingsPage(
                        onBack = { navController.popBackStack() },
                        onOpenRepair = { navController.navigate("repair") },
                        onOpenVerify = { navController.navigate("verify") },
                        onOpenDesktopLyric = { navController.navigate("desktopLyric") },
                        onOpenFallbackImport = { navController.navigate("fallback") { launchSingleTop = true } },
                        navigator = AppRootNavigator,
                    )
                }
                composable("verify") { com.yinyue.player.ui.settings.VerificationQueuePage(onBack = { navController.popBackStack() }) }
                composable("desktopLyric") { com.yinyue.player.ui.lyric.DesktopLyricSettingsPage(onBack = { navController.popBackStack() }) }
                composable("repair") { com.yinyue.player.ui.settings.EntityRepairPage(onBack = { navController.popBackStack() }) }
                composable("downloads") {
                    com.yinyue.player.ui.download.DownloadPage(
                        onBack = { navController.popBackStack() },
                        navigator = AppRootNavigator,
                    )
                }
                composable("player") { PlayerScreen(onBack = { navController.popBackStack() }) }
            }
        }
        if (showBottomBar) {
            MiniPlayerBar(onOpenPlayer = { navController.navigate("player") })
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = YinYueColors.Red,
                            selectedTextColor = YinYueColors.Red,
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

/** 常驻迷你播放条（对齐落雪 PlayBar Mini 版：封面 + 歌名 + 状态文本 + 进度线 + 控制） */
@Composable
private fun MiniPlayerBar(onOpenPlayer: () -> Unit) {
    val state by PlayerController.state.collectAsState()
    val now by com.yinyue.player.ui.player.PlaybackCoordinatorHolder.nowPlaying.collectAsState()
    val hasSong = now.songName.isNotBlank()

    // 封面旋转（播放时转）
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
            )
        }
    }

    if (!hasSong) return // 无播放内容时不显示（保持界面干净）

    // 播放位置单独订阅：只让进度线重组，避免整条迷你条每秒重组多次
    val position by PlayerController.position.collectAsState()
    Column(modifier = Modifier.fillMaxWidth()) {
        // 顶部进度线（时长基准：平台元数据优先）
        val displayDuration = now.durationMs?.takeIf { it > 0 } ?: state.durationMs
        val progress = if (displayDuration > 0) {
            (position.toFloat() / displayDuration).coerceIn(0f, 1f)
        } else {
            0f
        }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(1.5.dp),
            color = YinYueColors.Red,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Square,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlayer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 圆形封面（有真实封面用图，否则渐变占位）
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .rotate(rotation.value)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(YinYueColors.Red, YinYueColors.RedDark))),
                contentAlignment = Alignment.Center,
            ) {
                if (!now.coverUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = now.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    now.songName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 状态文本（对齐落雪 statusText：正在获取链接/切源中/B站MV 等）
                Text(
                    when {
                        now.switching -> "正在切换音源…"
                        now.exhausted -> now.exhaustedReason.ifBlank { "暂无可用音源" }
                        now.mvMode -> "MV · ${now.linksSummary}"
                        now.linksSummary.isNotBlank() -> "${now.artistName} · ${now.linksSummary}"
                        else -> now.artistName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (now.exhausted) YinYueColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.material3.IconButton(onClick = { PlayerController.togglePlayPause() }) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = YinYueColors.Red,
                )
            }
            androidx.compose.material3.IconButton(onClick = { PlayerController.seekToNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一曲", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
