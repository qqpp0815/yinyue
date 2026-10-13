package com.yinyue.player.ui.navigation

/**
 * 导航 deep seam（候选 3）：非导航页只依赖这一个小接口表达「我要去哪」，
 * 不直接碰全局可变 signal，也不依赖 Compose NavController。
 *
 * - 生产：AppRoot 侧由 [AppRootNavigator] 作为 adapter 消费 pending action；
 * - 测试 / Preview：传入 [RecordingNavigator] 记录意图，无需起 NavHost。
 */
interface Navigator {
    fun openSettings()
    fun openFallbackImport()
}

/** 记录式 adapter：把导航意图收集起来供断言，不产生真实跳转 */
class RecordingNavigator : Navigator {
    private val _opened = mutableListOf<String>()
    val opened: List<String> get() = _opened.toList()

    override fun openSettings() {
        _opened += "settings"
    }

    override fun openFallbackImport() {
        _opened += "fallback"
    }
}