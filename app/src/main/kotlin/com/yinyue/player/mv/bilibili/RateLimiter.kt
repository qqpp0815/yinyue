package com.yinyue.player.mv.bilibili

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局令牌桶（v3.0 §5.5）：B站请求 1 次/1.5 秒；合规条款与风控规避双重约束。
 * 单例：搜索/详情/取流/首页 全部 B站请求共享同一节流器（412 风控的直接对策）。
 */
object BiliRateLimiter {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L

    /**
     * B站请求运行期配置（由 App 启动时从设置读入，改设置立即生效）。
     * intervalMs 只会被 SettingsRepository 约束为 ≥1500ms，合规下限不可突破。
     */
    object Config {
        @Volatile
        var intervalMs: Long = 1_500L

        /** 画质偏好（SettingsRepository 的 BILI_QUALITY 原值） */
        @Volatile
        var quality: String = "AUTO"

        /** 用户自备 Cookie（可空 = 匿名） */
        @Volatile
        var userCookie: String? = null

        /** 按偏好与是否登录解析实际请求的 qn（480P=32 / 720P=64 / 1080P=80） */
        fun qn(): Int {
            val loggedIn = !userCookie.isNullOrBlank()
            return when (quality) {
                "P1080" -> 80
                "P720" -> 64
                "P480" -> 32
                else -> if (loggedIn) 80 else 32 // AUTO：匿名只能 480P，有 Cookie 拉最高
            }
        }
    }

    suspend fun acquire(minIntervalMs: Long = Config.intervalMs) {
        acquireWithBudget(timeBudgetMs = 0L, minIntervalMs = minIntervalMs)
    }

    /**
     * 播放热路径使用：在预算内返回 true；超过预算不再排队。
     * 返回 false 不代表被永久风控，只表示本次起播不能继续等下一次 1.5s 令牌。
     */
    suspend fun acquireWithBudget(timeBudgetMs: Long, minIntervalMs: Long = Config.intervalMs): Boolean {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = nextAllowedAt - now
            if (wait > 0) {
                if (timeBudgetMs <= 0L || wait >= timeBudgetMs) return false
                delay(wait)
            }
            nextAllowedAt = System.currentTimeMillis() + minIntervalMs
        }
        return true
    }
}
