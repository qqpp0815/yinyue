package com.yinyue.player.script

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class ScriptHealthProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val ok: Int = 0,
    val failed: Int = 0,
)

/**
 * 已导入脚本源健康检测：完整加载脚本并等待 inited，真实写回连通性状态。
 * 并发 3，兼顾速度与移动端网络/脚本服务端压力；不修改优先级，只修正展示状态。
 */
object ScriptHealthChecker {
    private val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(ScriptHealthProgress())
    val progress: StateFlow<ScriptHealthProgress> = _progress

    suspend fun checkAll(context: Context): ScriptHealthProgress = withContext(dispatcher) {
        mutex.withLock {
            val app = context.applicationContext
            val manager = ScriptManager.get(app)
            val scripts = manager.enabledScripts()
            _progress.value = ScriptHealthProgress(running = true, total = scripts.size)
            Log.i("ScriptHealth", "check start count=${scripts.size}")

            val ok = coroutineScope {
                scripts.map { script ->
                    async {
                        _progress.value = _progress.value.copy(current = script.name)
                        val passed = manager.connectTest(script.id)
                        Log.i("ScriptHealth", "${if (passed) "ok" else "failed"} ${script.name}")
                        passed
                    }
                }.awaitAll()
            }.count { it }

            val result = ScriptHealthProgress(
                running = false,
                done = scripts.size,
                total = scripts.size,
                current = if (ok == scripts.size) "全部正常" else "部分失败",
                ok = ok,
                failed = scripts.size - ok,
            )
            _progress.value = result
            Log.i("ScriptHealth", "check done ok=${result.ok} failed=${result.failed}")
            result
        }
    }
}
