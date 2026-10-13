package com.yinyue.player.ui.settings

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yinyue.player.ui.theme.YinYueColors

/**
 * 实体合并/拆分工具（v3.0 §13 R7）：归一层误判的手动修复。
 * 选择两个实体 → 合并（先选源、后选目标）；或对单个实体拆分出新实体。
 */
@Composable
fun EntityRepairPage(onBack: () -> Unit) {
    val vm: EntityRepairViewModel = viewModel()
    val state by vm.state.collectAsState()
    var splitTarget by remember { mutableStateOf<com.yinyue.player.data.db.entity.MusicEntity?>(null) }
    var splitName by remember { mutableStateOf("") }
    var splitArtist by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text("实体合并 / 拆分", style = MaterialTheme.typography.titleLarge)
                Text(
                    "归一层误判时手动修复（先点源实体，再点目标实体）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.message?.let {
            Text(
                it,
                color = YinYueColors.Red,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.clearMessage() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 ${state.selected.size}/2", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::mergeSelected, enabled = state.selected.size == 2) { Text("合并选中") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 0.5.dp)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.entities, key = { it.id }) { e ->
                val idx = state.selected.indexOf(e.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleSelect(e.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (idx >= 0) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (idx == 0) YinYueColors.Red else Color(0xFF4CAF50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (idx == 0) "源" else "目标",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(e.songName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${e.artistName} · #${e.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = {
                        splitTarget = e
                        splitName = e.songName + " (拆分)"
                        splitArtist = e.artistName
                    }) { Text("拆分") }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    thickness = 0.5.dp,
                )
            }
        }
    }

    splitTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { splitTarget = null },
            title = { Text("拆分为新实体") },
            text = {
                Column {
                    Text(
                        "将 #${target.id}「${target.songName}」的全部音源链接拆到新实体",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = splitName,
                        onValueChange = { splitName = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("新实体歌名") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = splitArtist,
                        onValueChange = { splitArtist = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("新实体歌手") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.splitEntity(target.id, splitName, splitArtist)
                    splitTarget = null
                }) { Text("拆分") }
            },
            dismissButton = { TextButton(onClick = { splitTarget = null }) { Text("取消") } },
        )
    }
}
