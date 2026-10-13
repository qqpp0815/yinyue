package com.yinyue.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.domain.normalize.MetadataNormalizer
import com.yinyue.player.ui.navigation.AppRootNavigator
import com.yinyue.player.ui.navigation.Navigator
import com.yinyue.player.ui.theme.YinYueColors

/**
 * 设置页（对齐网易云）：分组标题 + 列表行（左标题、右值/开关），分组间留距。
 * 分组：播放与音源 / MV 主线 / 缓存 / 验证 / 进阶 / 关于
 */
@Composable
fun SettingsPage(
    onBack: () -> Unit,
    onOpenRepair: () -> Unit = {},
    onOpenVerify: () -> Unit = {},
    onOpenDesktopLyric: () -> Unit = {},
    onOpenFallbackImport: () -> Unit = {},
    navigator: Navigator? = null,
) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsState()
    var relay by remember(state.douyinRelay) { mutableStateOf(state.douyinRelay) }
    var aliasA by remember { mutableStateOf("") }
    var aliasB by remember { mutableStateOf("") }
    var showAliasDialog by remember { mutableStateOf(false) }
    var showRelayDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showQualityPrefDialog by remember { mutableStateOf(false) }
    var showPlayModeDialog by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf<String?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var cookieInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 顶栏
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }
        }

        // ① 播放与音源
        item { GroupTitle("播放与音源") }
        item {
            SettingRow("去重模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.dedupMode == MetadataNormalizer.DedupMode.SONG_ONLY,
                        onClick = { vm.setDedupMode(MetadataNormalizer.DedupMode.SONG_ONLY) },
                        label = { Text("仅歌名", style = MaterialTheme.typography.bodySmall) },
                    )
                    FilterChip(
                        selected = state.dedupMode == MetadataNormalizer.DedupMode.SONG_AND_ARTIST,
                        onClick = { vm.setDedupMode(MetadataNormalizer.DedupMode.SONG_AND_ARTIST) },
                        label = { Text("歌名+歌手", style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }
        item {
            SettingRowSwitch("自动切源", state.autoSwitch) { vm.setAutoSwitch(it) }
        }
        item {
            SettingRowSwitch(
                "失败自动切下一首",
                state.autoSkip,
                subtitle = "当前歌所有音源都试完仍失败时，自动播放队列下一首（含息屏；连跳 5 首熔断）",
                onChange = { vm.setAutoSkip(it) },
            )
        }
        item {
            SettingRow(
                "桌面歌词",
                subtitle = "字号/透明度/颜色/多行/暂停隐藏/锁定（对齐落雪 desktopLyric 设置）",
                // 用 isEnabled（用户意图）而非 isShowing：pauseHide 下未播放时窗口没挂但仍属"已开启"
                value = if (com.yinyue.player.ui.lyric.DesktopLyricController.isEnabled) "已开启" else "已关闭",
                onClick = onOpenDesktopLyric,
            )
        }
        item {
            SettingRow(
                "切歌模式",
                subtitle = "对齐落雪：列表循环 / 随机 / 顺序 / 单曲循环 / 不切换",
                value = com.yinyue.domain.play.PlayModeLogic.label(state.playMode),
                onClick = { showPlayModeDialog = true },
            )
        }
        item {
            SettingRow(
                "优先播放音质",
                subtitle = "上限语义：选 128K 就只请求 128K（不会自动给更高）；想要「能多好就多好」请选「自动（尽可能高）」",
                value = com.yinyue.domain.match.QualityPreference.label(state.preferredQuality),
                onClick = { showQualityPrefDialog = true },
            )
        }

        // ② MV 主线
        item { GroupTitle("MV 主线（B站）") }
        item {
            SettingRowSwitch("缓存 MV 画面", state.cacheMvVideo, subtitle = "默认关闭，仅缓存音轨省空间") {
                vm.setCacheMvVideo(it)
            }
        }
        item {
            SettingRow(
                "画质偏好",
                subtitle = if (state.biliCookieSet) "已登录：自动取最高（1080P）" else "匿名上限 480P；填登录 Cookie 可到 1080P",
                value = qualityLabel(state.biliQuality),
                onClick = { showQualityDialog = true },
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row {
                    Text("请求限频", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "1 次 / ${"%.1f".format(state.biliRateLimitSec)} 秒",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = state.biliRateLimitSec,
                    onValueChange = { vm.setBiliRateLimitSec(it) },
                    valueRange = 1.5f..5.0f,
                    steps = 6,
                )
                Text(
                    "合规下限 1 次/1.5 秒，只能调更保守（越大越慢、越不容易触发风控）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingRow(
                "登录 Cookie（可选）",
                subtitle = "仅本机保存、非功能依赖；不填也能正常搜索与播放",
                value = if (state.biliCookieSet) "已设置" else "未设置",
                onClick = { showCookieDialog = true },
            )
        }

        // ③ 缓存
        item { GroupTitle("缓存") }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row {
                    Text("音频配额", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text("${state.audioQuotaGb.toInt()} GB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = state.audioQuotaGb,
                    onValueChange = { vm.setAudioQuotaGb(it.toInt().toFloat()) },
                    valueRange = 1f..20f,
                    steps = 18,
                )
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row {
                    Text("视频配额", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state.videoQuotaGb <= 0f) "不缓存" else "${state.videoQuotaGb.toInt()} GB",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = state.videoQuotaGb,
                    onValueChange = { vm.setVideoQuotaGb(it.toInt().toFloat()) },
                    valueRange = 0f..50f,
                    steps = 49,
                )
            }
        }
        item {
            SettingRow("一键清理双缓存", onClick = vm::clearCaches)
        }

        // ④ 原唱验证
        item { GroupTitle("原唱验证") }
        item {
            SettingRow(
                "待定确认队列",
                subtitle = "多平台投票无法定论的实体；人工确认权重最高",
                value = "${state.pendingCount} 项",
                onClick = onOpenVerify,
            )
        }
        item {
            SettingRow("别名表（英文名 → 中文名）", value = "添加", onClick = { showAliasDialog = true })
        }
        item {
            SettingRow(
                "实体合并 / 拆分",
                subtitle = "归一层误判时手动修复（同名拆开、误并合并）",
                value = "打开",
                onClick = onOpenRepair,
            )
        }

        // ⑤ 进阶
        item { GroupTitle("进阶") }
        item {
            SettingRow(
                "抖音解析中继地址",
                value = if (state.douyinRelay.isBlank()) "未设置" else "已设置",
                onClick = { showRelayDialog = true },
            )
        }
        item {
            SettingRow(
                "崩溃日志",
                subtitle = "闪退排查用：记录最后一次崩溃堆栈",
                value = if (crashText == null) "无" else "有",
                onClick = {
                    crashText = com.yinyue.player.CrashLogger.read(appContext)
                    showCrashDialog = true
                },
            )
        }

        // ⑥ 关于
        item { GroupTitle("关于") }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                // 版本号取自 build.gradle.kts（versionName），发版递增即自动同步到界面
                Text(
                    "音悦 · v${com.yinyue.player.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "安卓聚合音乐播放器（个人学习版）\n\n" +
                        "合规与自我约束条款：\n" +
                        "1. 本应用仅限个人学习研究，不以任何形式分发或商用；\n" +
                        "2. 严格限频：B站全局 1 次/1.5 秒令牌桶，搜索 24 小时本地缓存，禁止任何批量爬取行为；\n" +
                        "3. 无账号自动化：不请求用户提供任何平台账号密码；B站登录 Cookie 仅为可选画质增强，非功能依赖；\n" +
                        "4. 版权内容本地缓存仅限个人离线播放，不提供导出/分享功能；\n" +
                        "5. 抖音解析功能属尽力而为，存在失效风险；\n" +
                        "6. 所有外部 JS 脚本由用户自行导入，本应用不内置、不分发任何脚本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        state.message?.let { msg ->
            item {
                Text(
                    msg,
                    color = YinYueColors.Red,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showPlayModeDialog) {
        AlertDialog(
            onDismissRequest = { showPlayModeDialog = false },
            title = { Text("切歌模式") },
            text = {
                Column {
                    Text(
                        "与落雪一致的五种模式；播放页左下角是随机开关、右下角是循环开关",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    com.yinyue.domain.play.PlayMode.entries.forEach { m ->
                        Text(
                            (if (state.playMode == m) "● " else "○ ") +
                                com.yinyue.domain.play.PlayModeLogic.label(m),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setPlayMode(m)
                                    showPlayModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showPlayModeDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showQualityPrefDialog) {
        AlertDialog(
            onDismissRequest = { showQualityPrefDialog = false },
            title = { Text("优先播放音质") },
            text = {
                Column {
                    Text(
                        "与落雪一致：从所选档位往下找，不会向上越档。\n" +
                            "例：选「无损」→ 先要 flac，没有就退 320K，再退 128K。\n" +
                            "免费音源常只提供 128K/320K，选高档位时会自动降级，不会播放失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    com.yinyue.domain.match.QualityPreference.CHOICES.forEach { q ->
                        Text(
                            (if (state.preferredQuality == q) "● " else "○ ") +
                                com.yinyue.domain.match.QualityPreference.label(q) +
                                (if (q == state.preferredQuality) "（当前）" else ""),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setPreferredQuality(q)
                                    showQualityPrefDialog = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showQualityPrefDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("画质偏好") },
            text = {
                Column {
                    listOf(
                        "AUTO" to "自动（匿名 480P；有 Cookie 取最高）",
                        "P480" to "480P",
                        "P720" to "720P（需登录 Cookie）",
                        "P1080" to "1080P（需登录 Cookie）",
                    ).forEach { (key, label) ->
                        Text(
                            (if (state.biliQuality == key) "● " else "○ ") + label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setBiliQuality(key)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showQualityDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("登录 Cookie（可选）") },
            text = {
                Column {
                    Text(
                        "怎么获取（任选一种）：\n" +
                            "① 电脑浏览器登录 bilibili.com → 按 F12 → Application（应用）→ Storage → Cookies → " +
                            "选中 https://www.bilibili.com → 找到 SESSDATA，复制它的值，回来填成 SESSDATA=值\n" +
                            "② 装「Get cookies.txt LOCALLY」这类浏览器扩展，导出后复制其中 bilibili 的那一行\n" +
                            "③ 已登录的浏览器里按 F12 → Console，输入 document.cookie 回车，复制输出（至少含 SESSDATA）\n\n" +
                            "填了能解锁 720P/1080P；不填也能正常搜索与播放（匿名 480P）。\n" +
                            "Cookie 等同账号凭证：只保存在本机、不会上传，请勿分享给他人。\n" +
                            "本应用不会索要你的账号密码，也不做任何自动登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("至少填 SESSDATA=xxxxxx；也可整行粘贴 SESSDATA=..; bili_jct=..") },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.setBiliCookie(cookieInput)
                        showCookieDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        cookieInput = ""
                        vm.setBiliCookie("")
                        showCookieDialog = false
                    },
                ) { Text("清除") }
            },
        )
    }

    if (showAliasDialog) {
        AlertDialog(
            onDismissRequest = { showAliasDialog = false },
            title = { Text("添加别名") },
            text = {
                Column {
                    Text(
                        "英文名/艺名 → 中文标准名（新搜索生效）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = aliasA,
                        onValueChange = { aliasA = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("别名，如 Jay Chou") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = aliasB,
                        onValueChange = { aliasB = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("标准名，如 周杰伦") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addAlias(aliasA, aliasB)
                    aliasA = ""
                    aliasB = ""
                    showAliasDialog = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAliasDialog = false }) { Text("取消") } },
        )
    }

    if (showCrashDialog) {
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text("崩溃日志") },
            text = {
                Column {
                    val log = crashText
                    if (log.isNullOrBlank()) {
                        Text("暂无崩溃记录。")
                    } else {
                        Text(
                            "把下面内容发给开发者即可定位（只保留最后一次）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = log,
                                onValueChange = {},
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!crashText.isNullOrBlank()) {
                    TextButton(onClick = {
                        com.yinyue.player.CrashLogger.clear(appContext)
                        crashText = null
                    }) { Text("清空") }
                }
            },
            dismissButton = { TextButton(onClick = { showCrashDialog = false }) { Text("关闭") } },
        )
    }

    if (showRelayDialog) {
        AlertDialog(
            onDismissRequest = { showRelayDialog = false },
            title = { Text("抖音解析中继地址") },
            text = {
                Column {
                    Text(
                        "抖音官方分享页会对多数网络返回验证页（风控），此时解析只能靠中继。" +
                            "这里可填多个地址，用逗号分隔，按顺序依次尝试。\n\n" +
                            "自建只需满足极简契约：请求 {中继}/{视频ID} 返回一行音频直链（纯文本），" +
                            "或返回 JSON（audio_url / video_url / desc / author）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = relay,
                        onValueChange = { relay = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("https://your-relay/,https://backup-relay/") },
                        singleLine = false,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDouyinRelay(relay)
                    showRelayDialog = false
                    if (navigator != null) navigator.openFallbackImport() else onOpenFallbackImport()
                }) { Text("保存并返回导入") }
            },
            dismissButton = { TextButton(onClick = { showRelayDialog = false }) { Text("取消") } },
        )
    }
}

/** 画质偏好显示名 */
private fun qualityLabel(q: String): String = when (q) {
    "P480" -> "480P"
    "P720" -> "720P"
    "P1080" -> "1080P"
    else -> "自动"
}

/** 分组标题（网易云样式：小字灰色，上方留距） */
@Composable
    private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 设置行：左标题（+副标题）/ 右值或自定义内容 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun SettingRowSwitch(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle = subtitle, trailing = { Switch(checked = checked, onCheckedChange = onChange) })
}
