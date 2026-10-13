package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.cache.VideoStore
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.prefs.SettingsRepository

/**
 * video_store 配额的执行者（v3.0 §8：音频/视频双配额独立治理）。
 *
 * 此前 `VideoStore.evictIfNeeded` 写好了却没有任何调用方，设置页的「视频配额」滑杆只是个显示，
 * 拉到底也不会淘汰任何文件；「0 = 不缓存画面」也没有消费方。这里把三件事接上：
 * 配额 > 0 时按 LRU 淘汰到配额内（收藏实体的目录受保护），配额 ≤ 0 时视为不缓存画面。
 */
object VideoCacheJanitor {

    /** 视频配额（字节）。≤ 0 表示不缓存画面（只落音轨） */
    suspend fun quotaBytes(context: Context): Long =
        runCatching { SettingsRepository.get(context).videoQuotaBytes() }.getOrDefault(0L)

    /** 是否允许缓存画面（「缓存 MV 画面」开关 ∧ 配额 > 0） */
    suspend fun shouldCacheVideo(context: Context, userEnabled: Boolean): Boolean =
        userEnabled && quotaBytes(context) > 0

    /** 按当前配额执行一次淘汰；返回被淘汰的实体 id。配额 ≤ 0 时不做淘汰（只禁新画面） */
    suspend fun enforce(context: Context): List<Long> = runCatching {
        val quota = quotaBytes(context)
        if (quota <= 0) return@runCatching emptyList()
        val favorites = runCatching { AppDatabase.get(context).musicDao().favoriteIds() }.getOrDefault(emptyList())
        VideoStore.evictIfNeeded(context, quota, favorites.toSet())
    }.getOrDefault(emptyList())
}
