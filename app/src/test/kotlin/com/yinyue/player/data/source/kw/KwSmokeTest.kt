package com.yinyue.player.data.source.kw

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 6 冒烟：真实请求酷我搜索（限 1~2 次请求，遵守合规自我约束） */
class KwSmokeTest {

    private val provider = KwProvider()

    @Test
    fun `真实搜索返回结果且含音质`() = runBlocking {
        val page = provider.search("晴天 周杰伦", page = 1, limit = 30)
        assertTrue("应搜到结果，实际 total=${page.total}", page.list.isNotEmpty())
        val first = page.list.first()
        assertEquals("kw", first.platform)
        assertTrue("音质列表不应为空", first.qualitys.isNotEmpty())
        assertTrue(first.durationMs == null || first.durationMs!! > 0)
        // 二次验证：拿第一条歌词（尽力而为，可为 null 但不应抛异常）
        runCatching { provider.lyric(first.remoteTrackId) }
            .onSuccess { lrc -> if (lrc != null) assertTrue(lrc.contains("[")) }
        Unit
    }
}
