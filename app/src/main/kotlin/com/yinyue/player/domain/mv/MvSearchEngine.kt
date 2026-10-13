package com.yinyue.player.domain.mv

import android.content.Context
import com.google.gson.Gson
import com.yinyue.domain.mv.MvFilterChain
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.mv.bilibili.BiliClient
import com.yinyue.player.mv.bilibili.BiliClientException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** B站风控退避异常（-352 等），上层据此指数退避并标注失败 */
class MvRateLimitedException(message: String) : Exception(message)

/**
 * 音源2 搜索引擎（Task 16/17）：WBI 搜索 + 五步过滤链 + MvLink 落库。
 * 限频 1.5s 令牌桶；同实体 24h 内复用已落库 MvLink 不重发；
 * -352 → 指数退避交由调用方（搜索失败隔离不受影响）。
 */
class MvSearchEngine private constructor(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val client = BiliClient()
    private val gson = Gson()
    private var wbiKeys: BiliClient.WbiKeys? = null
    private var wbiFetchedAt = 0L
    private var backoffUntil = 0L
    private var backoffStep = 0

    suspend fun searchMvFor(music: MusicEntity, topN: Int = 5): List<MvLink> {
        val now = System.currentTimeMillis()
        if (now < backoffUntil) throw MvRateLimitedException("B站退避中，剩余 ${(backoffUntil - now) / 1000}s")

        // 同实体 24h 内复用已落库结果（搜索缓存语义）
        val existing = db.mvLinkDao().listByMusic(music.id)
        if (existing.isNotEmpty() && existing.any { (it.cachedAt ?: 0) > now - CACHE_TTL_MS }) {
            return existing
        }

        val keyword = listOf(music.songName, music.artistName).filter { it.isNotBlank() }.joinToString(" ") + " MV"
        val keys = try {
            ensureWbiKeys()
        } catch (e: Exception) {
            throw MvRateLimitedException("WBI 密钥获取失败：${e.message}")
        }
        val candidates = try {
            client.searchVideoCandidates(keyword, keys)
        } catch (e: Exception) {
            if (isRateLimited(e)) scheduleBackoff()
            throw MvRateLimitedException(e.message ?: "B站搜索失败")
        }
        val scored = MvFilterChain.filter(
            candidates = candidates.map {
                MvFilterChain.Candidate(
                    it.bvid,
                    com.yinyue.domain.normalize.NameCleaner.cleanMvTitle(it.title), // 展示名清洗
                    it.description,
                    it.durationSec,
                    it.author,
                    it.mid,
                    it.play,
                    it.officialVerify,
                    it.type,
                    it.coverUrl,
                )
            },
            songName = music.songName,
            artistName = music.artistName,
            entityDurationMs = music.durationMs,
        )
        val top = scored.take(topN)
        db.mvLinkDao().deleteByMusicId(music.id) // 旧候选替换（置信度序）
        val links = top.map { s ->
            MvLink(
                musicId = music.id,
                sourcePlatform = "bilibili",
                remoteId = s.candidate.id, // bvid
                title = s.candidate.title,
                upOwner = s.candidate.author,
                matchConfidence = s.confidence,
                cacheState = com.yinyue.player.data.db.entity.CacheState.REMOTE,
                cachedAt = System.currentTimeMillis(),
            )
        }
        if (links.isNotEmpty()) db.mvLinkDao().insertAll(links)
        backoffStep = 0
        // 原唱验证融合（Task 24）：B站信号回写实体级结论
        runCatching { fuseOriginality(music, top) }
        return links
    }

    private suspend fun fuseOriginality(
        music: MusicEntity,
        top: List<com.yinyue.domain.mv.MvFilterChain.Scored>,
    ) {
        if (music.verifiedOriginal == com.yinyue.player.data.db.entity.OriginalState.USER_CONFIRMED) return
        val first = top.firstOrNull() ?: return
        val matched = true
        val official = first.candidate.official || first.candidate.title.contains("官方")
        val durationMatch = music.durationMs != null &&
            Math.abs(first.candidate.durationSec * 1000 - music.durationMs) <= 5_000
        val coverConflict = listOf("翻唱", "cover").any {
            (first.candidate.title + first.candidate.description).lowercase().contains(it)
        }
        val result = com.yinyue.domain.verify.OriginalityVerifier.fuse(
            com.yinyue.domain.verify.OriginalityVerifier.SignalA(platformTagOriginal = null), // 平台标签尽力而为，暂中性
            com.yinyue.domain.verify.OriginalityVerifier.SignalB(matched, official, durationMatch, coverConflict),
        )
        val state = when (result.state) {
            com.yinyue.domain.verify.OriginalityVerifier.State.ORIGINAL ->
                com.yinyue.player.data.db.entity.OriginalState.ORIGINAL
            com.yinyue.domain.verify.OriginalityVerifier.State.PENDING ->
                com.yinyue.player.data.db.entity.OriginalState.PENDING
            else -> com.yinyue.player.data.db.entity.OriginalState.SUSPECT_COVER
        }
        db.musicDao().updateVerified(music.id, state, result.score)
    }

    /** MV-only 首搜（无实体时长基准）：直接用关键词搜，实体由结果兜底创建 */
    suspend fun searchMvByKeyword(keyword: String, topN: Int = 10): List<MvFilterChain.Scored> {
        val now = System.currentTimeMillis()
        if (now < backoffUntil) throw MvRateLimitedException("B站退避中")
        val keys = ensureWbiKeys(3_000L)
        val candidates = try {
            client.searchVideoCandidates("$keyword MV", keys)
        } catch (e: BiliClientException) {
            if (e.message?.contains("code=-352") == true || e.message?.contains("code=-412") == true) {
                scheduleBackoff()
            }
            throw e
        }
        val parts = keyword.trim().split(Regex("\\s+"), 2)
        return MvFilterChain.filter(
            candidates = candidates.map {
                MvFilterChain.Candidate(
                    it.bvid,
                    com.yinyue.domain.normalize.NameCleaner.cleanMvTitle(it.title), // 展示名清洗
                    it.description,
                    it.durationSec,
                    it.author,
                    it.mid,
                    it.play,
                    it.officialVerify,
                    it.type,
                    it.coverUrl,
                )
            },
            songName = parts.firstOrNull() ?: keyword,
            artistName = parts.getOrNull(1) ?: "",
            entityDurationMs = null,
            durationRequired = false,
            strictVersionFilter = false, // MV 关键词搜索：只滤合集，保留现场/live 等
        ).take(topN)
    }

    /**
     * 兜底搜索（宽松模式）：无脚本/全失效经 B站音轨出声时使用。
     * 只做 type==video + 黑名单过滤 + 歌名命中，不强制歌手（B站标题歌手写法差异大）。
     */
    suspend fun searchMvRelaxed(music: MusicEntity, topN: Int = 5): List<com.yinyue.domain.mv.MvFilterChain.Scored> {
        val now = System.currentTimeMillis()
        if (now < backoffUntil) throw MvRateLimitedException("B站退避中")
        val keys = ensureWbiKeys()
        val keyword = listOf(music.songName, music.artistName).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = try {
            client.searchVideoCandidates(keyword, keys)
        } catch (e: Exception) {
            throw MvRateLimitedException(e.message ?: "B站搜索失败")
        }
        return com.yinyue.domain.mv.MvFilterChain.filter(
            candidates = candidates.map {
                com.yinyue.domain.mv.MvFilterChain.Candidate(
                    it.bvid,
                    com.yinyue.domain.normalize.NameCleaner.cleanMvTitle(it.title),
                    it.description,
                    it.durationSec,
                    it.author,
                    it.mid,
                    it.play,
                    it.officialVerify,
                    it.type,
                    it.coverUrl,
                )
            },
            songName = music.songName,
            artistName = "", // 宽松：不要求歌手命中
            entityDurationMs = music.durationMs,
            durationRequired = false,
            strictVersionFilter = false, // 兜底场景：只滤合集
        ).take(topN)
    }

    private suspend fun ensureWbiKeys(budgetMs: Long = 0L): BiliClient.WbiKeys {
        client.fetchBuvidCookie(budgetMs)
        wbiKeys?.let { if (System.currentTimeMillis() - wbiFetchedAt < 3600_000) return it }
        val keys = client.fetchWbiKeys(budgetMs)
        wbiKeys = keys
        wbiFetchedAt = System.currentTimeMillis()
        return keys
    }

    private fun scheduleBackoff() {
        val delays = longArrayOf(30_000, 60_000, 120_000)
        backoffUntil = System.currentTimeMillis() + delays[backoffStep.coerceAtMost(delays.size - 1)]
        backoffStep++
    }

    /** 风控判定：业务码 -352/-412 或 HTTP 412（B站短时限频） */
    private fun isRateLimited(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("code=-352") || m.contains("code=-412") || m.contains("HTTP 412")
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 3600 * 1000

        @Volatile
        private var instance: MvSearchEngine? = null

        fun get(context: Context): MvSearchEngine =
            instance ?: synchronized(this) {
                instance ?: MvSearchEngine(context.applicationContext).also { instance = it }
            }
    }
}
