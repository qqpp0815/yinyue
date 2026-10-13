package com.yinyue.player.ui.source

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.script.ScriptManager
import com.yinyue.player.script.ScriptHealthChecker
import com.yinyue.player.script.SourceBatchProber
import com.yinyue.player.script.SourceProbePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 待用户确认的脚本更新 */
data class PendingScriptUpdate(
    val scriptId: String,
    val scriptName: String,
    val oldVersion: String,
    val newVersion: String,
    val newText: String,
)

data class SourceManageUiState(
    val message: String? = null,
    val busyId: String? = null,
    val pendingUpdate: PendingScriptUpdate? = null,
    val batchFailureSummary: String? = null,
)

/** 内置已验证可用的活跃源（GitHub 持续维护，任一失效不影响其他源） */
data class CuratedSource(val title: String, val description: String, val url: String)

class SourceManageViewModel(app: Application) : AndroidViewModel(app) {

    private val scriptManager = ScriptManager.get(app)
    private val db = AppDatabase.get(app)

    val scripts = db.userScriptDao().all()

    val batchProbeProgress = SourceBatchProber.progressState
    val healthProgress = ScriptHealthChecker.progress

    fun checkAllSources() {
        viewModelScope.launch {
            val result = ScriptHealthChecker.checkAll(getApplication())
            _state.value = _state.value.copy(
                message = when {
                    result.total == 0 -> "没有可检测的启用音源"
                    result.failed == 0 -> "检测完成：全部 ${result.ok} 个音源正常"
                    else -> "检测完成：正常 ${result.ok}，失败 ${result.failed}"
                },
            )
        }
    }

    fun startBatchProbe() {
        if (batchProbeProgress.value.phase !in setOf(
                SourceProbePhase.IDLE,
                SourceProbePhase.DONE,
                SourceProbePhase.FAILED,
            )
        ) {
            return
        }
        viewModelScope.launch {
            val result = SourceBatchProber.probeAndImport(getApplication())
            _state.value = _state.value.copy(
                message = "候选 ${result.candidates}，可用 ${result.healthy}，导入 ${result.imported}，失败 ${result.failed}",
                batchFailureSummary = if (result.healthy == 0 && result.candidates > 0) {
                    "全部候选未通过：${SourceBatchProber.progressState.value.failureSummary}"
                } else {
                    null
                },
            )
        }
    }

    private val _state = MutableStateFlow(SourceManageUiState())
    val state: StateFlow<SourceManageUiState> = _state

    /** 本地导入：读取所选脚本文件（仅支持在线导入与本地导入，不做剪贴板导入） */
    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "导入中…")
            try {
                val text = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("读取文件为空")
                val entity = scriptManager.importScript(text)
                _state.value = _state.value.copy(
                    message = "已导入「${entity.name}」，连通性测试${if (entity.lastTestOk == true) "通过" else "失败"}",
                )
            } catch (e: com.yinyue.script.InvalidLxScriptException) {
                _state.value = _state.value.copy(message = "无效的自定义源文件：${e.message}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "导入失败：${e.message}")
            }
        }
    }

    /** 在线导入（对齐 lx：输入 HTTP 链接下载脚本） */
    fun importFromUrl(url: String) {
        if (url.isBlank()) {
            _state.value = _state.value.copy(message = "请输入 HTTP 链接")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "下载中…")
            try {
                val entity = scriptManager.importFromUrl(url)
                _state.value = _state.value.copy(
                    message = "已导入「${entity.name}」，连通性测试${if (entity.lastTestOk == true) "通过" else "失败"}",
                )
            } catch (e: com.yinyue.script.InvalidLxScriptException) {
                _state.value = _state.value.copy(message = e.message ?: "导入失败")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "导入失败：${e.message}")
            }
        }
    }

    /** 一键导入内置活跃源；同 URL 已导入则跳过 */
    fun importCurated(source: CuratedSource) {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "正在下载「${source.title}」…")
            try {
                val existing = db.userScriptDao().allOnce()
                if (existing.any { it.sourceUrl == source.url }) {
                    _state.value = _state.value.copy(message = "「${source.title}」已导入，无需重复")
                    return@launch
                }
                val entity = scriptManager.importFromUrl(source.url)
                _state.value = _state.value.copy(
                    message = "已导入「${entity.name}」，连通性测试${if (entity.lastTestOk == true) "通过" else "未通过（服务端可能限流，可稍后重测）"}",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "导入「${source.title}」失败：${e.message}")
            }
        }
    }

    fun test(id: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = id, message = "测试中…")
            val ok = scriptManager.connectTest(id)
            // 失败时带上脚本自己输出的最后一句（常见于"脚本检测到需更新后拒绝初始化"）
            val reason = scriptManager.lastConsoleMessage(id)?.takeIf { !ok }
            _state.value = _state.value.copy(
                busyId = null,
                message = if (ok) "连通性测试通过" else "连通性测试失败：脚本未完成初始化" + (reason?.let { "（脚本输出：${it}）" } ?: ""),
            )
        }
    }

    /** 加载失败原因（脚本自己的最后一句输出），供卡片展示 */
    fun failureReason(id: String): String? = scriptManager.lastConsoleMessage(id)

    fun toggle(id: String, enabled: Boolean) {
        viewModelScope.launch { scriptManager.setEnabled(id, enabled) }
    }

    fun reload(id: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = id, message = "热加载中…")
            val ok = scriptManager.hotReload(id)
            _state.value = _state.value.copy(busyId = null, message = if (ok) "热加载成功" else "热加载失败")
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { scriptManager.remove(id) }
    }

    /** 检查更新：比对远端内容；有变化则弹确认框（不静默替换） */
    fun checkUpdate(id: String) {
        viewModelScope.launch {
            val name = db.userScriptDao().findById(id)?.name ?: "脚本"
            _state.value = _state.value.copy(busyId = id, message = "检查更新中…")
            when (val r = scriptManager.checkUpdate(id)) {
                is ScriptManager.ScriptUpdateResult.UpToDate ->
                    _state.value = _state.value.copy(busyId = null, message = "已是最新（${verText(r.version)}）")

                is ScriptManager.ScriptUpdateResult.HasUpdate ->
                    _state.value = _state.value.copy(
                        busyId = null,
                        message = null,
                        pendingUpdate = PendingScriptUpdate(
                            scriptId = id,
                            scriptName = name,
                            oldVersion = r.oldVersion,
                            newVersion = r.newVersion,
                            newText = r.newText,
                        ),
                    )

                ScriptManager.ScriptUpdateResult.NoSourceUrl ->
                    _state.value = _state.value.copy(
                        busyId = null,
                        message = "该脚本是本地/剪贴板导入，没有来源地址，请手动重新导入",
                    )

                is ScriptManager.ScriptUpdateResult.Failed ->
                    _state.value = _state.value.copy(busyId = null, message = "检查更新失败：${r.message}")
            }
        }
    }

    fun confirmUpdate() {
        val p = _state.value.pendingUpdate ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(pendingUpdate = null, busyId = p.scriptId, message = "更新中…")
            val msg = scriptManager.applyUpdate(p.scriptId, p.newText)
            _state.value = _state.value.copy(busyId = null, message = msg)
        }
    }

    fun cancelUpdate() {
        _state.value = _state.value.copy(pendingUpdate = null)
    }

    /** 上移/下移优先级（取链尝试顺序） */
    fun movePriority(id: String, up: Boolean) {
        viewModelScope.launch {
            scriptManager.movePriority(id, up)
            _state.value = _state.value.copy(message = if (up) "已上移优先级" else "已下移优先级")
        }
    }

    fun dismissReport(id: String) {
        viewModelScope.launch { scriptManager.dismissReportedUpdate(id) }
    }

    fun setAllowUpdateAlert(id: String, allow: Boolean) {
        viewModelScope.launch { scriptManager.setAllowUpdateAlert(id, allow) }
    }

    private fun verText(v: String) = if (v.isBlank()) "版本未标注" else "v$v"

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
