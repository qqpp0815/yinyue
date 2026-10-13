package com.yinyue.player.ui.lyric

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yinyue.player.data.prefs.SettingsRepository
import kotlinx.coroutines.launch

/**
 * 桌面歌词设置页（对应落雪设置里的 desktopLyric 分区）。
 * 改动即时生效（重建悬浮窗），无需重启。
 */
@Composable
fun DesktopLyricSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(DesktopLyricController.currentStyle()) }
    // 用"用户意图"而不是 isShowing 初始化：pauseHide 下未播放时窗口没挂，但开关仍是开着的
    var enabled by remember { mutableStateOf(DesktopLyricController.isEnabled) }
    var canDraw by remember { mutableStateOf(DesktopLyricController.canDraw(context)) }
    var pendingWant by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        style = DesktopLyricStyle.load(context)
        DesktopLyricController.applyStyle(context, style)
    }

    // 去系统设置授权后回到本页：重新判定权限；若用户在授权前已把开关拨开，则自动开启
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDraw = DesktopLyricController.canDraw(context)
                if (pendingWant && canDraw) {
                    pendingWant = false
                    DesktopLyricController.show(context)
                    enabled = DesktopLyricController.isEnabled
                    scope.launch {
                        SettingsRepository.get(context).setDesktopLyric(enabled)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun persist(new: DesktopLyricStyle) {
        style = new
        DesktopLyricController.applyStyle(context, new)
        scope.launch { DesktopLyricStyle.save(context, new) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("桌面歌词", style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示桌面歌词", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (canDraw) "置顶显示当前歌词；单击歌词唤出控制按钮，拖动可移动"
                        else "需要「显示在其他应用上层」权限（点右侧开关去授权）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        canDraw = DesktopLyricController.canDraw(context)
                        if (want && !canDraw) {
                            pendingWant = true
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        } else if (want) {
                            DesktopLyricController.show(context)
                            enabled = DesktopLyricController.isEnabled
                            scope.launch {
                                com.yinyue.player.data.prefs.SettingsRepository.get(context)
                                    .setDesktopLyric(enabled)
                            }
                        } else {
                            pendingWant = false
                            DesktopLyricController.hide()
                            enabled = false
                            scope.launch {
                                com.yinyue.player.data.prefs.SettingsRepository.get(context)
                                    .setDesktopLyric(false)
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("暂停时隐藏", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "与落雪一致：暂停或未在播放时不显示悬浮窗",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = style.pauseHide, onCheckedChange = { persist(style.copy(pauseHide = it)) })
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("多行显示", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "当前行高亮 + 上下相邻行（单行则只显示当前行）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = style.multiLine, onCheckedChange = { persist(style.copy(multiLine = it)) })
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("锁定", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "锁定后不可拖动、不显示控制按钮（也可在悬浮窗上双击切换）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = style.locked, onCheckedChange = { persist(style.copy(locked = it)) })
            }

            Spacer(Modifier.height(18.dp))
            Text("字号 ${"%.0f".format(style.fontSizeSp)}sp", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = style.fontSizeSp,
                onValueChange = { persist(style.copy(fontSizeSp = it)) },
                valueRange = 12f..34f,
                steps = 10,
            )

            Spacer(Modifier.height(8.dp))
            Text("行间距 ${"%.0f".format(style.lineGapSp)}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = style.lineGapSp,
                onValueChange = { persist(style.copy(lineGapSp = it)) },
                valueRange = 0f..40f,
                steps = 7,
            )

            Spacer(Modifier.height(8.dp))
            Text("不透明度 ${"%.0f".format(style.opacity * 100)}%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = style.opacity,
                onValueChange = { persist(style.copy(opacity = it)) },
                valueRange = 0.3f..1f,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("当前行放大", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "当前行比相邻行更大更醒目（对齐 isZoomActiveLrc）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = style.zoomActive, onCheckedChange = { persist(style.copy(zoomActive = it)) })
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("文字阴影", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "深色桌面/壁纸上保证可读性（对齐 lyricShadowColor）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = style.shadowColor != 0,
                    onCheckedChange = { persist(style.copy(shadowColor = if (it) 0x2E000000 else 0)) },
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("超长省略", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "过长歌词用「…」截断（对齐 style.ellipsis）；关闭则超出裁切",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = style.ellipsis, onCheckedChange = { persist(style.copy(ellipsis = it)) })
            }

            Spacer(Modifier.height(16.dp))
            Text("水平对齐", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("left" to "左对齐", "center" to "居中", "right" to "右对齐").forEach { (value, name) ->
                    TextButton(onClick = { persist(style.copy(align = value)) }) {
                        Text(
                            name,
                            color = if (style.align == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("当前行颜色", style = MaterialTheme.typography.bodyLarge)
            ColorRow(selected = style.playedColor) { persist(style.copy(playedColor = it)) }
            Spacer(Modifier.height(12.dp))
            Text("相邻行颜色", style = MaterialTheme.typography.bodyLarge)
            ColorRow(selected = style.unplayedColor) { persist(style.copy(unplayedColor = it)) }

            Spacer(Modifier.height(18.dp))
            TextButton(onClick = { persist(style.copy(x = null, y = null)) }) {
                Text("重置位置")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 预设颜色行（落雪是任意取色器；触屏这里给常用预设，够用且不弹系统取色器） */
@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    val presets = listOf(
        0xFFFF5C5C.toInt() to "红",
        0xFFFFFFFF.toInt() to "白",
        0xFF4FC3F7.toInt() to "蓝",
        0xFF81C784.toInt() to "绿",
        0xFFFFD54F.toInt() to "黄",
        0xFFE8E8E8.toInt() to "浅灰",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        presets.forEach { (color, name) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onPick(color) },
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(32.dp)) {
                        drawCircle(color = Color(color))
                        if (color == selected) {
                            drawCircle(color = Color.Black, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                        }
                    }
                }
                Text(name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
