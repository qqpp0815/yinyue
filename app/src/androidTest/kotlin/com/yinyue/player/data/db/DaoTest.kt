package com.yinyue.player.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinyue.player.data.db.entity.AudioLink
import com.yinyue.player.data.db.entity.CacheState
import com.yinyue.player.data.db.entity.MvLink
import com.yinyue.player.data.db.entity.MusicEntity
import com.yinyue.player.data.db.entity.OriginalState
import com.yinyue.player.data.db.entity.Playlist
import com.yinyue.player.data.db.entity.PlaylistMusicRef
import com.yinyue.player.data.db.entity.SearchCacheEntity
import com.yinyue.player.data.db.entity.UserScriptEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Task 4 验收：in-memory Room 覆盖键查重/排序/级联/歌单 position/缓存过期 */
@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun music(name: String, artist: String, key: String) = MusicEntity(
        songName = name, artistName = artist, normalizedKey = key,
        normalizedSong = name, normalizedArtist = artist, createdAt = 1L,
    )

    @Test
    fun normalizedKey查重与验证回写() = runBlocking {
        val dao = db.musicDao()
        val id = dao.insert(music("晴天", "周杰伦", "晴天::周杰伦"))
        assertTrue(dao.findByNormalizedKey("晴天::周杰伦") != null)
        assertNull(dao.findByNormalizedKey("七里香::周杰伦"))
        dao.updateVerified(id, OriginalState.ORIGINAL, 0.85f)
        val row = dao.findById(id)!!
        assertEquals(OriginalState.ORIGINAL, row.verifiedOriginal)
        assertEquals(0.85f, row.originalScore!!)
    }

    @Test
    fun 音源链接按音质_脚本优先级_响应时间排序() = runBlocking {
        val musicId = db.musicDao().insert(music("晴天", "周杰伦", "晴天::周杰伦"))
        val dao = db.audioLinkDao()
        // priorityScore 由生产路径（SearchDispatcher 调 PriorityScorer.score）写入：音质×10 + 原唱×2 + 来源权重
        dao.insertAll(
            listOf(
                AudioLink(musicId = musicId, scriptId = "s1", platform = "kw", remoteTrackId = "a", qualityLevel = 3, scriptPriority = 5, responseTimeMs = 100, priorityScore = 30),
                AudioLink(musicId = musicId, scriptId = "s1", platform = "kw", remoteTrackId = "b", qualityLevel = 4, scriptPriority = 1, responseTimeMs = 900, priorityScore = 40),
                AudioLink(musicId = musicId, scriptId = "s2", platform = "wy", remoteTrackId = "c", qualityLevel = 3, scriptPriority = 9, responseTimeMs = 500, priorityScore = 30),
                AudioLink(musicId = musicId, scriptId = "s2", platform = "wy", remoteTrackId = "d", qualityLevel = 3, scriptPriority = 9, responseTimeMs = 200, priorityScore = 30),
            ),
        )
        val ordered = dao.listByMusicOrdered(musicId)
        // 排序键 = 优先级分（音质×10+原唱×2+来源权重）> 脚本优先级 > 响应时间：
        // b(40) → d(30,p9,200ms) → c(30,p9,500ms) → a(30,p5)
        assertEquals(listOf("b", "d", "c", "a"), ordered.map { it.remoteTrackId })
        dao.updateCacheState(ordered.first().id, CacheState.EXPIRED)
        assertEquals(3, dao.listValidByMusic(musicId).size)
    }

    @Test
    fun 本地文件链接不受失效过滤影响() = runBlocking {
        // 已落盘（localPath 非空）的链接即使被标 EXPIRED 也要带回来：
        // 否则一次联网失败会让下载/兜底导入的文件永久无法离线播放
        val musicId = db.musicDao().insert(music("红尘客栈", "周杰伦", "红尘客栈::周杰伦"))
        val dao = db.audioLinkDao()
        val localId = dao.insert(
            AudioLink(
                musicId = musicId, scriptId = "-", platform = "local", remoteTrackId = "f.mp3",
                localPath = "/data/local/tmp/f.mp3", cacheState = CacheState.LOCAL, priorityScore = 1,
            ),
        )
        dao.insert(
            AudioLink(
                musicId = musicId, scriptId = "s", platform = "kw", remoteTrackId = "x",
                cacheState = CacheState.REMOTE, priorityScore = 30,
            ),
        )
        dao.updateCacheState(localId, CacheState.EXPIRED)
        // 普通"有效链接"查询会过滤掉它
        assertEquals(1, dao.listValidByMusic(musicId).size)
        // 播放用查询会保留它（本地文件优先分档的依据）
        val ordered = dao.listValidByMusicOrdered(musicId)
        assertEquals(2, ordered.size)
        assertEquals("f.mp3", ordered.first { it.id == localId }.remoteTrackId)
    }

    @Test
    fun 实体删除级联清理两类链接() = runBlocking {
        val musicId = db.musicDao().insert(music("晴天", "周杰伦", "晴天::周杰伦"))
        db.audioLinkDao().insert(AudioLink(musicId = musicId, scriptId = "s", platform = "kw", remoteTrackId = "x"))
        db.mvLinkDao().insert(MvLink(musicId = musicId, sourcePlatform = "bilibili", title = "MV"))
        db.musicDao().deleteByIds(listOf(musicId))
        assertEquals(0, db.audioLinkDao().listByMusicOrdered(musicId).size)
        assertEquals(0, db.mvLinkDao().listByMusic(musicId).size)
    }

    @Test
    fun 歌单position重排与歌曲顺序() = runBlocking {
        val pdao = db.playlistDao()
        val pid = pdao.insert(Playlist(name = "我的歌单", createdAt = 1L))
        val m1 = db.musicDao().insert(music("歌1", "a", "歌1::a"))
        val m2 = db.musicDao().insert(music("歌2", "b", "歌2::b"))
        val m3 = db.musicDao().insert(music("歌3", "c", "歌3::c"))
        pdao.addSong(PlaylistMusicRef(pid, m1, 0))
        pdao.addSong(PlaylistMusicRef(pid, m2, 1))
        pdao.addSong(PlaylistMusicRef(pid, m3, 2))
        // 拖拽：m3 挪到最前
        pdao.updateRefs(listOf(PlaylistMusicRef(pid, m3, 0), PlaylistMusicRef(pid, m1, 1), PlaylistMusicRef(pid, m2, 2)))
        assertEquals(listOf("歌3", "歌1", "歌2"), pdao.songsOf(pid).map { it.songName })
        assertEquals(3, pdao.nextPosition(pid))
        pdao.removeSong(pid, m3)
        assertEquals(2, pdao.songsOf(pid).size)
    }

    @Test
    fun 搜索缓存24小时过期() = runBlocking {
        val dao = db.searchCacheDao()
        dao.put(SearchCacheEntity("晴天::周杰伦|BOTH", "[]", expireAt = 1000L))
        assertNull(dao.getValid("晴天::周杰伦|BOTH", now = 1001L))
        dao.put(SearchCacheEntity("晴天::周杰伦|BOTH", "[1]", expireAt = 2000L))
        assertEquals("[1]", dao.getValid("晴天::周杰伦|BOTH", now = 1500L)?.resultJson)
        dao.purgeExpired(now = 3000L)
        assertNull(dao.getValid("晴天::周杰伦|BOTH", now = 3000L))
    }

    @Test
    fun 用户脚本CRUD与启停() = runBlocking {
        val dao = db.userScriptDao()
        dao.insert(UserScriptEntity(id = "user_api_1", name = "测试源", scriptText = "/** */", createdAt = 1L, priority = 3))
        dao.insert(UserScriptEntity(id = "user_api_2", name = "低优先", scriptText = "/** */", createdAt = 2L, priority = 1))
        dao.setEnabled("user_api_2", false)
        val enabled = dao.enabledScripts()
        assertEquals(listOf("user_api_1"), enabled.map { it.id })
        dao.updateTestResult("user_api_1", ts = 99L, ok = true)
        assertEquals(true, dao.findById("user_api_1")!!.lastTestOk)
        dao.delete("user_api_1")
        assertNull(dao.findById("user_api_1"))
    }
}
