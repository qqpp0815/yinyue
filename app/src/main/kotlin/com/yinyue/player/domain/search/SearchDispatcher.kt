package com.yinyue.player.domain.search

import android.content.Context
import com.google.gson.Gson
import com.yinyue.domain.normalize.AliasTable
import com.yinyue.domain.normalize.MetadataNormalizer
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.OriginalState
import com.yinyue.player.data.db.entity.SearchCacheEntity
import com.yinyue.player.data.db.entity.SearchHistory
import com.yinyue.player.data.prefs.SettingsRepository
import com.yinyue.player.data.source.SourceRegistry
import com.yinyue.player.data.source.model.RawTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * SearchDispatcher（Task 7，v3.0 §6）：
 * 音频线（音源1：多平台并发 → 归一去重合并）与 MV 线（音源2：B站独立搜索）**完全独立**，
 * 用户自行选择加入播放列表；失败隔离；搜索缓存（normalizedKey+mode，24h）。
 */
class SearchDispatcher private constructor(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val gson = Gson()

    /**
     * 当前生效的别名表（内置 + 用户在设置里补充的）。
     * 必须是实例状态而不是直接用 `AliasTable.DEFAULT`：v3.0 §4.1 要求"允许用户在设置中补充"，
     * 用 DEFAULT 会让设置页填的别名对归一层完全无效（填了等于没填）。
     */
    @Volatile
    private var aliasTable: AliasTable = AliasTable.DEFAULT

    /** 重新载入用户别名（启动时、以及设置页每次保存别名后调用） */
    suspend fun refreshAliasTable() {
        aliasTable = runCatching {
            AliasTable(SettingsRepository.get(context).userAliases().toMap())
        }.getOrDefault(AliasTable.DEFAULT)
    }

    data class CachePayload(
        val ids: List<Long>,
        val mv: List<MvResult>,
        val failed: List<String>,
        val artistName: String? = null,
    )

    suspend fun search(req: UnifiedSearchRequest): SearchResultPage {
        val settings = SettingsRepository.get(context)
        val dedupMode = settings.dedupMode()
        val alias = aliasTable
        val now = System.currentTimeMillis()
        db.searchCacheDao().purgeExpired(now)

        val cacheKey = "${MetadataNormalizer.normalizeSong(req.keyword)}|${req.mode}|${dedupMode}|p${req.page}"
        db.searchCacheDao().getValid(cacheKey, now)?.let { cached ->
            // 命中缓存：MV 线不粘滞（失败可重试）
            val restored = runCatching {
                restore(gson.fromJson(cached.resultJson, CachePayload::class.java), req, fromCache = true)
            }.getOrElse { SearchResultPage(req.keyword, req.mode, emptyList(), emptyList(), emptyList()) }
            if (req.mode == SearchMode.AUDIO_ONLY) return restored.copy(failedPlatforms = emptyList())
            val mvFailed = mutableListOf<String>()
            val mv = searchMvLine(req, mvFailed)
            return restored.copy(mvResults = mv, failedPlatforms = mvFailed)
        }

        val failed = mutableListOf<String>()

        // 音频线（音源1）
        var artistSearchName: String? = null
        val songs = if (req.mode != SearchMode.VIDEO_ONLY) {
            val rawTracks = coroutineScope {
                SourceRegistry.all().map { provider ->
                    async {
                        try {
                            withTimeout(8_000) { provider.search(req.keyword, req.page) }.list
                        } catch (e: Exception) {
                            failed.add(provider.platform)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            // 歌手搜索识别：关键词与歌手命中的比例足够高 → 视为"搜歌手"
            val keywordKey = com.yinyue.domain.normalize.NameCleaner.coreKey(req.keyword)
            val artistHits = rawTracks.count { com.yinyue.domain.normalize.NameCleaner.artistMatches(it.artistName, keywordKey) }
            val isArtistSearch = rawTracks.isNotEmpty() && artistHits >= 3 &&
                artistHits.toDouble() / rawTracks.size >= 0.4
            val filtered = if (isArtistSearch) {
                artistSearchName = rawTracks.firstOrNull {
                    com.yinyue.domain.normalize.NameCleaner.artistMatches(it.artistName, keywordKey)
                }?.artistName
                rawTracks
                    // ① 只保留该歌手的条目（排除同名歌曲/其他人的歌）
                    .filter { com.yinyue.domain.normalize.NameCleaner.artistMatches(it.artistName, keywordKey) }
                    // ② 歌名不能是歌手名（脏数据过滤）
                    .filterNot { com.yinyue.domain.normalize.NameCleaner.songNameEqualsArtist(it.songName, it.artistName) }
                    // ③ 过滤他人改编版（翻唱/钢琴版/纯音乐等，平台常把原唱标进歌手字段）
                    .filterNot { t ->
                        VERSION_NOISE.any { t.songName.contains(it, ignoreCase = true) }
                    }
            } else {
                rawTracks
            }
            mergeAndPersist(filtered, dedupMode, alias, now)
        } else {
            emptyList()
        }

        // MV 线（音源2，独立）
        val mvResults = if (req.mode != SearchMode.AUDIO_ONLY) {
            searchMvLine(req, failed)
        } else {
            emptyList()
        }

        val page = SearchResultPage(req.keyword, req.mode, songs, mvResults, failed, artistName = artistSearchName)
        db.searchCacheDao().put(
            SearchCacheEntity(
                cacheKey,
                gson.toJson(CachePayload(songs.map { it.music.id }, mvResults, failed, artistSearchName)),
                now + CACHE_TTL_MS,
            ),
        )
        db.searchHistoryDao().upsert(SearchHistory(req.keyword, now))
        return page
    }

    /** 去重键：核心名（去拼接/后缀/修饰）+ 歌手（模式②） */
    private fun coreKeyOf(
        t: RawTrack,
        dedupMode: MetadataNormalizer.DedupMode,
        alias: AliasTable,
    ): String {
        val core = com.yinyue.domain.normalize.NameCleaner.coreKey(t.songName)
        return when (dedupMode) {
            MetadataNormalizer.DedupMode.SONG_ONLY -> core
            MetadataNormalizer.DedupMode.SONG_AND_ARTIST -> core + "::" + MetadataNormalizer.normalizeArtist(t.artistName, alias)
        }
    }

    /** 时长综合：组内各源时长的众数（无则取中位数；全空才为 null）——不因第一条为空而丢弃 */
    private fun bestDurationOf(group: List<RawTrack>): Long? {
        val values = group.mapNotNull { it.durationMs }.filter { it > 0 }
        if (values.isEmpty()) return null
        val counts = values.groupingBy { it }.eachCount()
        val maxCount = counts.values.max()
        return counts.filterValues { it == maxCount }.keys.minOrNull()
            ?: values.sorted()[values.size / 2]
    }

    /** 用户已人工确认的原唱结论不被自动覆盖 */
    private fun OriginalState?.isUserConfirmed(): Boolean =
        this == OriginalState.USER_CONFIRMED

    /** MV 线：B站独立搜索（不依赖音频实体；有同名音频实体时记录关联仅用于展示） */
    private suspend fun searchMvLine(req: UnifiedSearchRequest, failed: MutableList<String>): List<MvResult> {
        return try {
            val scored = com.yinyue.player.domain.mv.MvSearchEngine.get(context)
                .searchMvByKeyword(req.keyword, topN = 20)
            // 关联同名音频实体（展示用，不影响独立播放）
            val linkedMusicId = db.musicDao().findByNormalizedKey(
                MetadataNormalizer.normalizedKey(
                    req.keyword, "", MetadataNormalizer.DedupMode.SONG_ONLY,
                ),
            )?.id
            scored.map { s ->
                MvResult(
                    bvid = s.candidate.id,
                    title = s.candidate.title,
                    author = s.candidate.author,
                    durationSec = s.candidate.durationSec,
                    play = s.candidate.playCount,
                    confidence = s.confidence,
                    coverUrl = s.candidate.coverUrl,
                    matchedMusicId = linkedMusicId,
                )
            }
        } catch (e: Exception) {
            if (failed.none { it.startsWith("bilibili") }) {
                failed.add("bilibili（${e.message?.take(60) ?: "搜索失败"}）")
            }
            emptyList()
        }
    }

    /**
     * 音频线归一合并（重点算法，两级去重）：
     * ① 精确：v3.0 normalizedKey（去括号/繁简/别名/大小写）相等直接同组
     * ② 模糊：落雪 SongMatcher（filterStr + 多歌手排序 + ±5s 时长 + 版本词一致 + 双向包含）判定同组
     * 命中已有实体则只追加 AudioLink，不新建；组内音源按 priorityScore 排序。
     */
    private suspend fun mergeAndPersist(
        rawTracks: List<RawTrack>,
        dedupMode: MetadataNormalizer.DedupMode,
        alias: AliasTable,
        now: Long,
    ): List<SongResult> {
        val seen = HashSet<String>()
        val tracks = rawTracks.filter {
            it.songName.isNotBlank() && seen.add("${it.platform}_${it.remoteTrackId}")
        }
        tracks.forEach { com.yinyue.player.domain.play.MusicUrlResolver.cacheRaw(it) } // 供取链使用

        // ① 精确分组（核心名 + 歌手；核心名已去拼接/后缀/修饰，加了料的一律并入同组）
        val exactGroups = LinkedHashMap<String, MutableList<RawTrack>>()
        for (t in tracks) {
            val key = coreKeyOf(t, dedupMode, alias)
            exactGroups.getOrPut(key) { mutableListOf() }.add(t)
        }

        // ② 模糊合并（落雪 SongMatcher：把近似组并入同一实体；仅 SONG_AND_ARTIST 模式启用，
        //     SONG_ONLY 模式本身已按核心歌名合并，无需再模糊）
        val mergedGroups: List<Pair<String, List<RawTrack>>> = if (dedupMode == MetadataNormalizer.DedupMode.SONG_AND_ARTIST) {
            val flattened = exactGroups.values.flatten()
            val playable = flattened.filter { it.artistName.isNotBlank() }
            val fuzzy = com.yinyue.domain.match.SongMatcher.groupSongs(playable) { t ->
                com.yinyue.domain.match.SongMatcher.Key.of(
                    name = com.yinyue.domain.normalize.NameCleaner.coreSongName(t.songName),
                    singer = t.artistName,
                    albumName = t.albumName,
                    intervalSec = ((t.durationMs ?: 0L) / 1000).toInt(),
                )
            }
            fuzzy.map { group ->
                val first = group.first()
                coreKeyOf(first, dedupMode, alias) to group
            }
        } else {
            exactGroups.map { (k, v) -> k to v.toList() }
        }

        val results = ArrayList<SongResult>(mergedGroups.size)
        for ((key, group) in mergedGroups) {
            val first = group.first()
            // 显示名用清洗后的核心名（"晴天+告白气球 (Live)" → "晴天"）
            val displayName = com.yinyue.domain.normalize.NameCleaner.coreSongName(first.songName)
            val songNorm = MetadataNormalizer.normalizeSong(displayName)
            val artistNorm = MetadataNormalizer.normalizeArtist(first.artistName, alias)
            // 时长综合：取组内各平台时长的众数（不因第一条为空就丢掉）
            val groupDuration = bestDurationOf(group)
            var entity = db.musicDao().findByNormalizedKey(key)
            if (entity == null) {
                // 命中已有实体（任一模糊成员）则复用，避免重复建实体
                outer@ for (t in group) {
                    val artistKey = MetadataNormalizer.normalizeArtist(t.artistName, alias)
                    val candidates = db.musicDao().findAllByArtistKey(artistKey)
                    val tKey = com.yinyue.domain.match.SongMatcher.Key.of(
                        t.songName, t.artistName, t.albumName, ((t.durationMs ?: 0L) / 1000).toInt(),
                    )
                    for (existing in candidates) {
                        val eKey = com.yinyue.domain.match.SongMatcher.Key.of(
                            existing.songName, existing.artistName, existing.albumName,
                            ((existing.durationMs ?: 0L) / 1000).toInt(),
                        )
                        if (com.yinyue.domain.match.SongMatcher.isSameSong(eKey, tKey)) {
                            entity = existing
                            break@outer
                        }
                    }
                }
            }
            val musicId: Long
            if (entity != null) {
                musicId = entity.id
                // 时长/封面补齐：用组内综合时长，封面后到者补（各平台封面可得性不同）
                val needDuration = groupDuration != null &&
                    (entity.durationMs == null || Math.abs(entity.durationMs - groupDuration) > 5000)
                val groupCover = group.firstNotNullOfOrNull { it.coverUrl?.takeIf { c -> c.isNotBlank() } }
                val needCover = entity.coverUrl.isNullOrBlank() && groupCover != null
                if (needDuration || needCover) {
                    db.musicDao().update(
                        entity.copy(
                            durationMs = if (needDuration) groupDuration else entity.durationMs,
                            coverUrl = if (needCover) groupCover else entity.coverUrl,
                        ),
                    )
                }
            } else {
                musicId = db.musicDao().insert(
                    MusicEntity(
                        songName = displayName, // 干净显示名（"晴天"）
                        artistName = first.artistName,
                        albumName = first.albumName,
                        coverUrl = group.firstNotNullOfOrNull { it.coverUrl?.takeIf { c -> c.isNotBlank() } },
                        durationMs = groupDuration, // 组内综合时长
                        normalizedKey = key,
                        normalizedSong = songNorm,
                        normalizedArtist = artistNorm,
                        createdAt = now,
                    ),
                )
            }
            // 原唱判定（v3.0 §4.2 信号源A）：按"歌名 - 演唱者"多源交叉投票（票数按平台去重）
            val artistVotes = com.yinyue.domain.match.OriginalityVoter.tally(
                group.map {
                    com.yinyue.domain.match.OriginalityVoter.Vote(
                        com.yinyue.domain.normalize.NameCleaner.coreKey(it.artistName),
                        it.platform,
                    )
                },
            )
            val links = ArrayList<AudioLink>(group.size)
            for (t in group) {
                if (db.audioLinkDao().findByPlatformTrack(t.platform, t.remoteTrackId) != null) continue
                val originTag = com.yinyue.domain.match.OriginalityVoter.tagOf(
                    com.yinyue.domain.normalize.NameCleaner.coreKey(t.artistName),
                    artistVotes,
                )
                links.add(
                    AudioLink(
                        musicId = musicId,
                        scriptId = "-",
                        platform = t.platform,
                        remoteTrackId = t.remoteTrackId,
                        artistKey = com.yinyue.domain.normalize.NameCleaner.coreKey(t.artistName),
                        qualityLevel = t.bestQuality.level,
                        musicInfoJson = t.toOldMusicInfoJson("").toString(), // 起播取链不再补搜
                        originTag = originTag,
                        priorityScore = com.yinyue.domain.match.PriorityScorer.score(
                            qualityLevel = t.bestQuality.level,
                            isOriginal = originTag,
                            // 经注册表判定：新增平台自动按音源1 参与排序
                            sourceWeight = com.yinyue.domain.match.PriorityScorer.audioSourceWeight(t.platform) {
                                SourceRegistry.get(it) != null
                            },
                        ),
                        cacheState = CacheState.REMOTE,
                    ),
                )
            }
            // 实体级原唱结论：多平台一致时判原唱（用户已人工确认的不覆盖）
            if (com.yinyue.domain.match.OriginalityVoter.hasTrustedOriginal(artistVotes) &&
                !entity?.verifiedOriginal.isUserConfirmed()
            ) {
                db.musicDao().updateVerified(musicId, OriginalState.ORIGINAL, null)
            }
            if (links.isNotEmpty()) db.audioLinkDao().insertAll(links)
            // 合并限量（用户要求"限制合并的数量，以原唱为主"）：
            // 同名合并会把大量他人翻唱并进同一实体（实测《勇气》117 条），
            // 原唱链接全留、翻唱限量，避免"切源逐条试上百链接"与"兜底播到别人的翻唱"
            enforceLinkCapsForEntity(musicId, entity?.artistName ?: first.artistName)
            results.add(
                SongResult(
                    music = db.musicDao().findById(musicId) ?: continue,
                    audioLinks = db.audioLinkDao().listByMusicOrdered(musicId),
                ),
            )
        }
        return results.sortedWith(
            compareByDescending<SongResult> { r -> r.audioLinks.any { it.cacheState == CacheState.LOCAL } }
                .thenByDescending { r -> r.audioLinks.maxOfOrNull { it.priorityScore } ?: 0 },
        )
    }

    /**
     * 全库整理：把"以原唱为主"的限量规则应用到**已有的所有实体**。
     * 只对搜索结果里出现的实体做裁剪会漏掉孤儿实体（实测存在两个《勇气》实体：新实体已按规则裁剪，
     * 旧实体 60 条原样留着），所以启动时扫一遍链接过多的实体。
     */
    suspend fun sweepLinkCaps() {
        val ids = db.audioLinkDao().musicIdsWithManyLinks(
            com.yinyue.domain.match.LinkCaps.MAX_COVER_LINKS,
        )
        for (id in ids) {
            val artist = db.musicDao().findById(id)?.artistName ?: ""
            runCatching { enforceLinkCapsForEntity(id, artist) }
        }
        if (ids.isNotEmpty()) {
            android.util.Log.i("YY-DL", "全库链接整理完成，检查 ${ids.size} 个实体")
        }
    }

    /**
     * 按"以原唱为主"裁剪该实体的链接：原唱全留、他人翻唱限量（见 LinkCaps）。
     *
     * 自包含：原唱**从库里已有链接重新投票**得出（按平台去重），因此搜索结果命中 24h 缓存时
     * （走 restore 路径、不经过合并）也能生效——这正是《勇气》117 条没被裁掉的原因。
     * 投票定不了原唱（单平台/歌手未知）时不裁剪，避免误删。
     */
    suspend fun enforceLinkCapsForEntity(musicId: Long, entityArtistName: String = "") {
        var links = db.audioLinkDao().listByMusicOrdered(musicId)
        // 数据修正（**每次搜索都跑，不受条件限制**）：
        //   ① 回填演唱者（artistKey 是 v4 新增，旧链接为空）
        //   ② 修正音质档位（bestQuality 曾按枚举序号比较取到最差档，旧链接普遍被记成 128K）
        //   ③ 按现行公式重算优先级分（公式已改为"原唱×100 + 音质×10 + 权重"）
        var fixed = 0
        links.forEach { l ->
            if (l.artistKey.isBlank()) {
                val artist = com.yinyue.player.domain.play.MusicUrlResolver.rawCache[l.platform]
                    ?.get(l.remoteTrackId)?.artistName
                val key = artist?.let { com.yinyue.domain.normalize.NameCleaner.coreKey(it) }.orEmpty()
                if (key.isNotBlank()) {
                    db.audioLinkDao().updateArtistKey(l.id, key)
                    fixed++
                }
            }
            val real = com.yinyue.player.domain.play.MusicUrlResolver.rawCache[l.platform]
                ?.get(l.remoteTrackId)?.bestQuality?.level
            val newLevel = if (real != null && real > l.qualityLevel) real else l.qualityLevel
            if (newLevel != l.qualityLevel) {
                db.audioLinkDao().updateQualityLevel(l.id, newLevel)
                fixed++
            }
            val recomputed = com.yinyue.domain.match.PriorityScorer.score(
                qualityLevel = newLevel,
                isOriginal = l.originTag,
                sourceWeight = com.yinyue.domain.match.PriorityScorer.audioSourceWeight(l.platform) {
                    SourceRegistry.get(it) != null
                },
            )
            if (recomputed != l.priorityScore) {
                db.audioLinkDao().updatePriorityScore(l.id, recomputed)
                fixed++
            }
        }
        if (fixed > 0) {
            android.util.Log.i("YY-DL", "实体 $musicId 修正 $fixed 条链接（演唱者/音质/优先级分）")
            links = db.audioLinkDao().listByMusicOrdered(musicId)
        }
        // 链接很少时无需计算（≤ 翻唱上限则一定没有可裁的）
        if (links.size <= com.yinyue.domain.match.LinkCaps.MAX_COVER_LINKS) return
        val entityKey = com.yinyue.domain.normalize.NameCleaner.coreKey(entityArtistName)
        val votes = com.yinyue.domain.match.OriginalityVoter.tally(
            links.map {
                com.yinyue.domain.match.OriginalityVoter.Vote(
                    it.artistKey.ifBlank { entityKey },
                    it.platform,
                )
            },
        )
        if (!com.yinyue.domain.match.OriginalityVoter.hasTrustedOriginal(votes)) return
        val mainstream = votes.maxByOrNull { it.value }?.key ?: return
        if (mainstream.isBlank()) return
        val keep = com.yinyue.domain.match.LinkCaps.keep(
            items = links.map {
                com.yinyue.domain.match.LinkCaps.Item(
                    id = it.id,
                    // 老数据没有 artistKey：用原唱兜底（不误判为翻唱、不误删）
                    artistKey = it.artistKey.ifBlank { mainstream },
                    priorityScore = it.priorityScore,
                )
            },
            originalArtistKey = mainstream,
        )
        val drop = links.filter { it.id !in keep }.map { it.id }
        if (drop.isNotEmpty()) {
            db.audioLinkDao().deleteByIds(drop)
            android.util.Log.i("YY-DL", "实体 $musicId 链接裁剪：保留 ${keep.size}，删除 ${drop.size}（原唱=$mainstream）")
        }
    }

    /**
     * 把榜单/歌单里的原始曲目落库成可播放的 SongResult（排行榜与推荐歌单共用）：
     * 建/复用实体 → 追加 AudioLink（音质取平台给出的最高档）→ 缓存原始 musicInfo 供脚本取链。
     * 与搜索结果的差异：不做跨平台去重合并（集合本身来自单一平台的既有排序，保序更符合预期）。
     */
    suspend fun materialize(tracks: List<RawTrack>): List<SongResult> {
        val settings = SettingsRepository.get(context)
        val alias = aliasTable
        val now = System.currentTimeMillis()
        val out = ArrayList<SongResult>(tracks.size)
        for (t in tracks) {
            com.yinyue.player.domain.play.MusicUrlResolver.cacheRaw(t) // 取链用
            val displayName = com.yinyue.domain.normalize.NameCleaner.coreSongName(t.songName)
            if (displayName.isBlank()) continue
            val artistNorm = MetadataNormalizer.normalizeArtist(t.artistName, alias)
            val songNorm = MetadataNormalizer.normalizeSong(displayName)
            val key = MetadataNormalizer.normalizedKey(displayName, t.artistName)
            var entity = db.musicDao().findByNormalizedKey(key)
            if (entity == null) {
                val id = db.musicDao().insert(
                    com.yinyue.player.data.db.entity.MusicEntity(
                        songName = displayName,
                        artistName = t.artistName,
                        albumName = t.albumName,
                        coverUrl = t.coverUrl,
                        durationMs = t.durationMs,
                        normalizedKey = key,
                        normalizedSong = songNorm,
                        normalizedArtist = artistNorm,
                        createdAt = now,
                    ),
                )
                entity = db.musicDao().findById(id)
            } else if (entity.coverUrl.isNullOrBlank() && !t.coverUrl.isNullOrBlank()) {
                // 补封面（榜单接口常带高清封面，搜索结果可能没有）
                db.musicDao().update(entity.copy(coverUrl = t.coverUrl, durationMs = entity.durationMs ?: t.durationMs))
            }
            val music = entity ?: continue
            if (db.audioLinkDao().findByPlatformTrack(t.platform, t.remoteTrackId) == null) {
                val artistVotes = com.yinyue.domain.match.OriginalityVoter.tally(
                    listOf(com.yinyue.domain.match.OriginalityVoter.Vote(
                        com.yinyue.domain.normalize.NameCleaner.coreKey(t.artistName), t.platform,
                    )),
                )
                db.audioLinkDao().insert(
                    com.yinyue.player.data.db.entity.AudioLink(
                        musicId = music.id,
                        scriptId = "",
                        platform = t.platform,
                        remoteTrackId = t.remoteTrackId,
                        qualityLevel = t.bestQuality.level,
                        musicInfoJson = t.toOldMusicInfoJson("").toString(), // 起播取链不再补搜
                        originTag = com.yinyue.domain.match.OriginalityVoter.tagOf(
                            com.yinyue.domain.normalize.NameCleaner.coreKey(t.artistName), artistVotes,
                        ),
                        priorityScore = com.yinyue.domain.match.PriorityScorer.score(
                            t.bestQuality.level,
                            com.yinyue.domain.match.OriginalityVoter.tagOf(
                                com.yinyue.domain.normalize.NameCleaner.coreKey(t.artistName), artistVotes,
                            ),
                            t.platform,
                        ),
                        responseTimeMs = null,
                    ),
                )
            }
            out.add(SongResult(music, db.audioLinkDao().listByMusicOrdered(music.id)))
        }
        // 预留：未来按设置项决定是否对集合内歌曲做跨平台去重（当前保持平台原有排序）
        settings.dedupMode()
        return out
    }

    private suspend fun restore(payload: CachePayload, req: UnifiedSearchRequest, fromCache: Boolean): SearchResultPage {
        val songs = payload.ids.mapNotNull { id ->
            val music = db.musicDao().findById(id) ?: return@mapNotNull null
            // 命中缓存也要过一遍"以原唱为主"的裁剪（老数据/裁剪规则上线前入库的实体在此被修正）
            enforceLinkCapsForEntity(id, music.artistName)
            SongResult(music, db.audioLinkDao().listByMusicOrdered(id))
        }
        return SearchResultPage(req.keyword, req.mode, songs, payload.mv, payload.failed, fromCache, payload.artistName)
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 3600 * 1000

        /** 歌手搜索时的版本噪声词（他人改编版/纯音乐，非该歌手原唱作品） */
        private val VERSION_NOISE = listOf(
            "钢琴", "纯音乐", "翻唱", "cover", "伴奏", "remix", "吉他", "古筝", "八音盒", "尤克里里",
            "口琴", "萨克斯", "小提琴版", "演奏", "轻音乐", "纯音", "DJ版", "dj版", "慢摇",
        )

        @Volatile
        private var instance: SearchDispatcher? = null

        fun get(context: Context): SearchDispatcher =
            instance ?: synchronized(this) {
                instance ?: SearchDispatcher(context.applicationContext).also { instance = it }
            }
    }
}
