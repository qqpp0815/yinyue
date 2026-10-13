package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState

/**
 * 音源健康 module（候选 2：统一音源健康）。
 *
 * 此前 AudioLink 的「可用 / 锁定直链 / 失效 / 落盘」写入散在
 * [MusicUrlResolver]、[SourcePreflight]、[SourceSession]、[PlaybackCoordinator]、
 * [DownloadManager] 五处，判定口径与写入顺序容易漂移（例如清锁与标 EXPIRED 顺序不一致，
 * 导致「误报全部失效」）。
 *
 * 这里把 cacheState / resolvedUrl / lastVerified 的写入收成一个 module：
 * 调用方只表达意图（可播、锁定、失效、本地、清锁），字段组合与顺序由实现负责。
 */
object SourceHealth {

    /** 解析/探测成功：置 REMOTE（本地文件不回退成 REMOTE）+ 刷新新鲜度 */
    suspend fun markPlayable(context: Context, link: AudioLink) {
        val dao = AppDatabase.get(context).audioLinkDao()
        if (link.cacheState != CacheState.LOCAL) dao.updateCacheState(link.id, CacheState.REMOTE)
        dao.updateLastVerified(link.id, System.currentTimeMillis())
    }

    /** 锁定最优源：写回持久化直链（A+ 后台锁定，重启/切歌直接复用） */
    suspend fun lockResolved(context: Context, linkId: Long, url: String, quality: String, scriptId: String) {
        AppDatabase.get(context).audioLinkDao()
            .updateResolved(linkId, url, System.currentTimeMillis(), quality, scriptId)
    }

    /** 明确失效：先清锁定直链，再标 EXPIRED（顺序固定，避免脏锁覆盖失效态） */
    suspend fun markExpired(context: Context, linkId: Long) {
        val dao = AppDatabase.get(context).audioLinkDao()
        dao.updateResolved(linkId, null, null, null, null)
        dao.updateCacheState(linkId, CacheState.EXPIRED)
    }

    /**
     * 仅清锁定直链（探测明确失败、或起播失败但尚未定论失效）。
     *
     * 同时作废内存组合缓存：两者缓存的是同一条 URL（TTL 均 30min），
     * 只清 DB 会让 `resolve()` 立刻从 combo 返回同一条死链，本会话内永远恢复不了。
     */
    suspend fun clearLock(context: Context, linkId: Long) {
        AppDatabase.get(context).audioLinkDao().updateResolved(linkId, null, null, null, null)
        MusicUrlResolver.invalidateCombo(linkId)
    }

    /** 落盘成功：记录本地文件并置 LOCAL（离线可播，不再依赖网络） */
    suspend fun markLocal(context: Context, linkId: Long, absolutePath: String) {
        AppDatabase.get(context).audioLinkDao().updateLocalPath(linkId, absolutePath, CacheState.LOCAL)
    }

    /** 本地文件仍有效：把误标 EXPIRED 的链接恢复为 LOCAL */
    suspend fun restoreLocal(context: Context, linkId: Long, absolutePath: String) {
        AppDatabase.get(context).audioLinkDao().updateLocalPath(linkId, absolutePath, CacheState.LOCAL)
    }
}