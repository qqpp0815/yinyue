package com.yinyue.player.ui.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.player.data.db.entity.UserScriptEntity
import com.yinyue.player.script.SourceProbePhase

/** 内置推荐：本次真机验证可用的 GitHub 活跃源 */
private val CuratedSources = listOf(
    CuratedSource(
        title = "1010音源（推荐）",
        description = "五大平台聚合直链，2026-10-10 更新，实测酷我直链可用",
        url = "https://raw.githubusercontent.com/Lingeng27/lx-music-1010-source/main/1010-mobile.js",
    ),
    CuratedSource(
        title = "1010音源·手机版",
        description = "同上，全链路 HTTPS + 四音质档（手机推荐）",
        url = "https://raw.githubusercontent.com/Lingeng27/lx-music-1010-source/main/1010.js",
    ),
)

/** 音源管理页（Task 12）：内置源分区说明 + 用户脚本卡片（导入/测试/启停/热加载/删除） */
@Composable
fun SourceManagePage(onBack: () -> Unit) {
    val vm: SourceManageViewModel = viewModel()
    val scripts by vm.scripts.collectAsState(initial = emptyList())
    val state by vm.state.collectAsState()
    val batchProgress by vm.batchProbeProgress.collectAsState()
    val healthProgress by vm.healthProgress.collectAsState()
    var showOnlineImport by remember { mutableStateOf(false) }
    var onlineUrl by remember { mutableStateOf("") }
    var showCurated by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFromFile(it) }
    }

    // 更新确认：发现新版本时弹出，用户确认后才替换
    val pending = state.pendingUpdate
    if (pending != null) {
        UpdateConfirmDialog(
            scriptName = pending.scriptName,
            oldVersion = pending.oldVersion,
            newVersion = pending.newVersion,
            onConfirm = { vm.confirmUpdate() },
            onDismiss = { vm.cancelUpdate() },
        )
    }

    if (showOnlineImport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOnlineImport = false },
            title = { Text("在线导入") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "输入洛雪格式脚本的 HTTP 链接（如 GitHub Raw 地址）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = onlineUrl,
                        onValueChange = { onlineUrl = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("https://...js") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importFromUrl(onlineUrl)
                    onlineUrl = ""
                    showOnlineImport = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showOnlineImport = false }) { Text("取消") } },
        )
    }

    if (showCurated) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCurated = false },
            title = { Text("推荐活跃源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "以下为 GitHub 持续维护、本次实测可用的聚合源；单个源失效不影响其他源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CuratedSources.forEach { src ->
                        OutlinedCard(
                            onClick = {
                                vm.importCurated(src)
                                showCurated = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(src.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    src.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCurated = false }) { Text("关闭") } },
        )
    }

    Scaffold(
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text("音源管理", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                state.batchFailureSummary?.let { summary ->
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = vm::clearMessage) { Text("知道了") }
            }
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("内置元数据源（不可删）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "kw 酷我 / wy 网易云 / kg 酷狗 / tx QQ音乐 / mg 咪咕 —— 提供搜索与元数据；播放链接由下方用户脚本解析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { showOnlineImport = true }) { Text("在线导入") }
                Button(onClick = { showCurated = true }) { Text("推荐活跃源") }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("本地导入") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = vm::checkAllSources, enabled = !healthProgress.running) {
                    Text(if (healthProgress.running) "检测中…" else "检测全部")
                }
                Button(onClick = vm::startBatchProbe) { Text("批量探测") }
            }
            if (healthProgress.running) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("正在检测已添加音源 ${healthProgress.done}/${healthProgress.total}：${healthProgress.current}")
                        Text("正常 ${healthProgress.ok} · 失败 ${healthProgress.failed}")
                        LinearProgressIndicator(
                            progress = {
                                if (healthProgress.total <= 0) 0f
                                else healthProgress.done.toFloat() / healthProgress.total
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
            if (batchProgress.phase in setOf(
                    SourceProbePhase.FETCHING,
                    SourceProbePhase.PROBING,
                    SourceProbePhase.IMPORTING,
                )
            ) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            when (batchProgress.phase) {
                                SourceProbePhase.FETCHING -> "正在抓取候选源…"
                                SourceProbePhase.PROBING -> "并行探测 ${batchProgress.done}/${batchProgress.total}：${batchProgress.current}"
                                SourceProbePhase.IMPORTING -> "导入健康源 ${batchProgress.done}/${batchProgress.total}"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "可用 ${batchProgress.healthy} · 已导入 ${batchProgress.imported}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (batchProgress.total <= 0) 0f
                                else batchProgress.done.toFloat() / batchProgress.total
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scripts, key = { it.id }) { script ->
                    ScriptCard(
                        script = script,
                        busy = state.busyId == script.id,
                        onToggle = { vm.toggle(script.id, it) },
                        onTest = { vm.test(script.id) },
                        onReload = { vm.reload(script.id) },
                        onRemove = { vm.remove(script.id) },
                        onCheckUpdate = { vm.checkUpdate(script.id) },
                        onDismissReport = { vm.dismissReport(script.id) },
                        onAllowAlert = { vm.setAllowUpdateAlert(script.id, it) },
                        onMoveUp = { vm.movePriority(script.id, true) },
                        onMoveDown = { vm.movePriority(script.id, false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(
    script: UserScriptEntity,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onReload: () -> Unit,
    onRemove: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDismissReport: () -> Unit,
    onAllowAlert: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(script.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        (if (script.version.isBlank()) "版本未标注" else "v${script.version}") +
                            " · ${script.author.ifBlank { "作者未标注" }} · 优先级 ${script.priority}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    script.lastTestOk?.let { ok ->
                        Text(
                            if (ok) "连通性：正常" else "连通性：失败（点击测试重试）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        if (script.sourceUrl.isBlank()) "来源：本地/剪贴板导入（无法检查更新）" else "来源：在线导入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = script.enabled, onCheckedChange = onToggle)
            }

            // 脚本自报更新（落雪对齐：接受脚本上报，由用户决定是否处理）
            val reported = parseReportedUpdate(script.reportedUpdateJson)
            if (reported != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        "脚本上报有新版本" + reported.let { if (it.version.isBlank()) "" else " ${it.version}" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (reported.description.isNotBlank()) {
                        Text(
                            reported.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCheckUpdate, enabled = !busy) { Text("检查更新") }
                        TextButton(onClick = onDismissReport) { Text("忽略") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onTest, enabled = !busy) { Text("测试") }
                TextButton(onClick = onReload, enabled = !busy) { Text("热加载") }
                TextButton(onClick = onCheckUpdate, enabled = !busy) { Text("检查更新") }
                TextButton(onClick = onRemove) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
            // 优先级：取链时按此顺序依次尝试（越靠上越先试）；并列会退化成"最后导入的最先试"，故需可调
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "优先级 ${script.priority}（越靠上越先尝试）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onMoveUp, enabled = !busy) { Text("上移") }
                TextButton(onClick = onMoveDown, enabled = !busy) { Text("下移") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "允许更新提醒",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = script.allowShowUpdateAlert,
                    onCheckedChange = onAllowAlert,
                    enabled = !busy,
                )
            }
        }
    }
}

/**
 * 脚本上报的更新信息。落雪契约是 `{ log, updateUrl }`（log 必填、超长截断），
 * 这里兼容部分脚本自行写的 version/description 字段。
 */
private data class ReportedUpdate(val version: String, val description: String, val updateUrl: String = "")

private fun parseReportedUpdate(json: String): ReportedUpdate? {
    if (json.isBlank()) return null
    return runCatching {
        val o = com.google.gson.JsonParser.parseString(json).asJsonObject
        fun str(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
        ReportedUpdate(
            version = str("version"),
            // 落雪：log 是给用户看的说明；没有 log 时退回 description
            description = str("log").ifBlank { str("description") },
            updateUrl = str("updateUrl"),
        )
    }.getOrNull()
}

/** 确认更新弹窗：展示 旧版本 → 新版本，用户确认后才替换 */
@Composable
private fun UpdateConfirmDialog(
    scriptName: String,
    oldVersion: String,
    newVersion: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本") },
        text = {
            Column {
                Text(scriptName, style = MaterialTheme.typography.titleSmall)
                Text(
                    (if (oldVersion.isBlank()) "版本未标注" else "v$oldVersion") +
                        "  →  " + (if (newVersion.isBlank()) "版本未标注" else "v$newVersion"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "更新将替换本地脚本内容并重新做连通性测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("更新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
