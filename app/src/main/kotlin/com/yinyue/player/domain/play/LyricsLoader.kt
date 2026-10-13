package com.yinyue.player.domain.play

import android.content.Context
import com.yinyue.player.data.db.AppDatabase
import com.yinyue.player.data.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 歌词加载 module：按已有 AudioLink 平台优先尝试，缺失时按实体名补搜；
 * MV 则按“核心歌名必须相等 + 歌手包含/时长接近”加载。结果通过 [onLyric] 回写。
 */
class LyricsLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLyric: (String?) -> Unit,
) {
    private val db = AppDatabase.get(context)
    private var currentMusicId: Long = 0L

    fun beginTrack(musicId: Long) {
        currentMusicId = musicId
        scope.launch {
            val music = db.musicDao().findById(musicId) ?: return@launch
            val links = db.audioLinkDao().listValidByMusicOrdered(musicId)
            var lrc: String? = null
            for (link in links) {
                val provider = SourceRegistry.get(link.platform) ?: continue
                lrc = runCatching { provider.lyric(link.remoteTrackId) }.getOrNull()
                if (!lrc.isNullOrBlank()) break
            }
            if (lrc.isNullOrBlank()) {
                val provider = links.firstOrNull()?.platform
                    ?.let { SourceRegistry.get(it) }
                    ?: SourceRegistry.all().firstOrNull()
                if (provider != null) {
                    lrc = runCatching {
                        val keyword = listOf(music.songName, music.artistName).filter { it.isNotBlank() }.joinToString(" ")
                        val page = provider.search(keyword, 1, 5)
                        val match = page.list.firstOrNull {
                            it.durationMs != null && music.durationMs != null &&
                                Math.abs(it.durationMs - music.durationMs) < 5000
                        } ?: page.list.firstOrNull()
                        match?.let { provider.lyric(it.remoteTrackId) }
                    }.getOrNull()
                }
            }
            if (currentMusicId == musicId) onLyric(lrc)
        }
    }

    fun loadForMv(rawTitle: String, artist: String, mvDurationMs: Long?) {
        scope.launch {
            val cleaned = com.yinyue.domain.normalize.NameCleaner.cleanMvTitle(rawTitle)
            val songName = com.yinyue.domain.normalize.NameCleaner.coreSongName(cleaned)
            if (songName.isBlank()) return@launch
            val songKey = com.yinyue.domain.normalize.NameCleaner.coreKey(songName)
            val artistKey = com.yinyue.domain.normalize.NameCleaner.coreKey(artist)
            for (provider in SourceRegistry.all()) {
                val lrc = runCatching {
                    val keyword = listOf(songName, artist).filter { it.isNotBlank() }.joinToString(" ")
                    val page = provider.search(keyword, 1, 10)
                    val match = page.list.firstOrNull { track ->
                        if (com.yinyue.domain.normalize.NameCleaner.coreKey(track.songName) != songKey) {
                            return@firstOrNull false
                        }
                        val trackArtistKey = com.yinyue.domain.normalize.NameCleaner.coreKey(track.artistName)
                        val artistOk = artistKey.isNotBlank() && trackArtistKey.isNotBlank() &&
                            (trackArtistKey.contains(artistKey) || artistKey.contains(trackArtistKey))
                        val durationOk = mvDurationMs != null && mvDurationMs > 0 && track.durationMs != null &&
                            Math.abs(track.durationMs - mvDurationMs) <= 10_000
                        artistOk || durationOk
                    }
                    match?.let { provider.lyric(it.remoteTrackId) }
                }.getOrNull()
                if (!lrc.isNullOrBlank()) {
                    onLyric(lrc)
                    return@launch
                }
            }
        }
    }
}
