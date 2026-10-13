package com.yinyue.player.ui.fallback

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.player.ui.navigation.AppRootNavigator
import com.yinyue.player.ui.navigation.Navigator

/** 兜底导入页（Task 21）：手动文件导入 + 抖音分享链接解析（UI 明示尽力而为） */
@Composable
fun FallbackImportPage(onBack: () -> Unit, navigator: Navigator = AppRootNavigator) {
    val navToSettings: () -> Unit = { navigator.openSettings() }
    val vm: FallbackViewModel = viewModel()
    val state by vm.state.collectAsState()
    var shareUrl by remember { mutableStateOf("") }
    // v1.0 §7.2「抖音 URL 解析（音频/MV 二选一）」：默认只落音频
    var withVideo by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.prepareFile(it) } // 先解析再弹可编辑表单（v3.0 §9.1）
    }

    // 可编辑元数据确认表单
    state.form?.let { form ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelForm() },
            title = { Text("确认导入信息") },
            text = {
                Column {
                    Text(
                        "文件名：${form.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = form.songName,
                        onValueChange = { vm.updateForm(form.copy(songName = it)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("歌名") },
                        singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = form.artistName,
                        onValueChange = { vm.updateForm(form.copy(artistName = it)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("歌手") },
                        singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = form.albumName,
                        onValueChange = { vm.updateForm(form.copy(albumName = it)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("专辑（可选）") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = vm::confirmImport) { Text("导入") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = vm::cancelForm) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text("兜底导入", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("手动文件导入", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "选择本地音频/视频文件，自动解析文件名元数据并归一入库；导入文件视为永久缓存，永不淘汰",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*", "video/*")) }, enabled = !state.busy) {
                        Text("选择文件")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("抖音分享链接解析", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "抖音 App → 分享 → 复制链接，粘贴到此处；解析成功即落盘。注意：抖音官方接口有风控，属尽力而为功能；若持续失败，可在 设置 → 进阶 配置自建解析中继地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = shareUrl,
                        onValueChange = { shareUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴分享链接") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = withVideo,
                            onCheckedChange = { withVideo = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "同时保存画面（默认只存音频，省空间；需要看 MV 画面时再开）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.importDouyin(shareUrl, withVideo) }, enabled = !state.busy) { Text("解析并导入") }
                        OutlinedButton(onClick = { navToSettings() }) { Text("配置中继") }
                    }
                    if (state.showRelayActions) {
                        Text(
                            "官方匿名链路已返回验证页/空响应；配置可用解析中继后重试成功率更高",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            state.message?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
