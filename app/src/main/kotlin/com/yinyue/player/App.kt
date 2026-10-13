package com.yinyue.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志：把最后一次未捕获异常落盘（设置页「崩溃日志」可查看），便于定位闪退
        com.yinyue.player.CrashLogger.install(this)
        // 五平台元数据源（Task 6 / 6a~6d），搜索池随注册自动生效
        com.yinyue.player.data.source.SourceRegistry.register(com.yinyue.player.data.source.kw.KwProvider())
        com.yinyue.player.data.source.SourceRegistry.register(com.yinyue.player.data.source.wy.WyProvider())
        com.yinyue.player.data.source.SourceRegistry.register(com.yinyue.player.data.source.kg.KgProvider())
        com.yinyue.player.data.source.SourceRegistry.register(com.yinyue.player.data.source.tx.TxProvider())
        com.yinyue.player.data.source.SourceRegistry.register(com.yinyue.player.data.source.mg.MgProvider())
        // 播放协调器注入（Task 14/15）
        com.yinyue.player.ui.player.PlaybackCoordinatorHolder.get = {
            com.yinyue.player.domain.play.PlaybackCoordinator.get(this)
        }
        // 提前建好协调器：它负责把"上一首/下一首"接到应用侧播放队列（按钮/滑动/播完续播）
        val coordinator = com.yinyue.player.domain.play.PlaybackCoordinator.get(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            coordinator.reloadPlayMode() // 切歌模式（落雪对齐，默认列表循环）
            // 桌面歌词：上次开着且仍有权限则自动恢复显示
            val s = com.yinyue.player.data.prefs.SettingsRepository.get(this@App)
            if (s.desktopLyric() && com.yinyue.player.ui.lyric.DesktopLyricController.canDraw(this@App)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // 必须先把持久化样式（字号/颜色/透明度/位置记忆）灌进去再挂窗，
                    // 否则重启后会按默认样式与默认位置显示，用户改过的设置全部丢失
                    val st = com.yinyue.player.ui.lyric.DesktopLyricStyle.load(this@App)
                    com.yinyue.player.ui.lyric.DesktopLyricController.applyStyle(this@App, st)
                    com.yinyue.player.ui.lyric.DesktopLyricController.show(this@App)
                }
            }
        }
        com.yinyue.player.player.PlayerController.connect(this)

        // 后台/锁屏自动降级为 audio-only（v3.0 §7.1：省流量省电）
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    com.yinyue.player.domain.play.PlaybackCoordinator.get(this@App).downgradeToAudioOnly()
                }
            },
        )

        // 音源2（B站）运行期配置：限频间隔 / 画质偏好 / 用户自备 Cookie
        // 设置页改动后同样调用这里，保证即时生效（限频下限由 SettingsRepository 约束）
        applyBiliSettingsAsync()

        // 脚本快照：供播放热路径读取"平台 → 脚本优先级"。
        // 热路径刻意不加载脚本（自禁用脚本要等 12s 超时会把起播拖死），所以启动时先刷一次。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val scriptManager = com.yinyue.player.script.ScriptManager.get(this@App)
            scriptManager.refreshScriptsSnapshot()
            // 引擎预加载（A：点歌秒起的关键）：取链必须已有引擎，否则冷启动要现场加载，
            // 单个脚本最坏 12s 超时。这里在后台并行加载好，用户真正点歌时直接命中；
            // 加载失败的脚本有 5 分钟冷却，不会每轮重复拖慢。
            runCatching {
                scriptManager.ensureEngines(
                    scriptManager.enabledScripts().map { it.id },
                    budgetMs = 20_000,
                )
            }
            runCatching {
                com.yinyue.player.script.ScriptHealthChecker.checkAll(this@App)
            }
            // 用户补充的别名（设置-验证组）必须在搜索前灌进归一层，否则那栏设置等于没接
            runCatching {
                com.yinyue.player.domain.search.SearchDispatcher.get(this@App).refreshAliasTable()
            }
            // 全库整理：把"以原唱为主"的合并限量应用到已有实体（含搜索不再命中的孤儿实体）
            runCatching {
                com.yinyue.player.domain.search.SearchDispatcher.get(this@App).sweepLinkCaps()
            }
            runCatching {
                com.yinyue.player.domain.play.SourceSession.observeNetwork(this@App)
                com.yinyue.player.domain.play.SourceSession.recover(this@App)
            }
        }
    }

    private fun applyBiliSettingsAsync() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            applyBiliSettings()
        }
    }

    private suspend fun applyBiliSettings() {
        val s = com.yinyue.player.data.prefs.SettingsRepository.get(this)
        com.yinyue.player.mv.bilibili.BiliRateLimiter.Config.intervalMs = s.biliRateLimitMs()
        com.yinyue.player.mv.bilibili.BiliRateLimiter.Config.quality = s.biliQuality()
        com.yinyue.player.mv.bilibili.BiliRateLimiter.Config.userCookie = s.biliCookie().takeIf { it.isNotBlank() }
    }

    /** Coil 图片加载：B站封面有防盗链，统一带 Referer 与浏览器 UA */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                                )
                                .header("Referer", "https://www.bilibili.com/")
                                .build(),
                        )
                    }
                    .build()
            }
            .crossfade(true)
            .build()
}
