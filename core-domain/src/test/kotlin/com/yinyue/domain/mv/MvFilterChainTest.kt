package com.yinyue.domain.mv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MvFilterChainTest {

    private fun c(
        id: String, title: String, durationSec: Long = 269, play: Long = 100_000,
        official: Boolean = false, author: String = "周杰伦Official", type: String = "video",
        description: String = "",
    ) = MvFilterChain.Candidate(id, title, description, durationSec, author, 1L, play, official, type)

    private fun run(cands: List<MvFilterChain.Candidate>, durationMs: Long? = 269_000L) =
        MvFilterChain.filter(cands, "晴天", "周杰伦", durationMs)

    @Test
    fun `非video与黑名单剔除`() {
        val out = run(
            listOf(
                c("1", "晴天 MV"),
                c("2", "晴天 MV", type = "special"),
                c("3", "晴天 翻唱版 MV"),
                c("4", "晴天 cover MV"),
                c("5", "晴天混剪"),
            ),
        )
        assertEquals(listOf("1"), out.map { it.candidate.id })
    }

    @Test
    fun `歌名或歌手未命中剔除`() {
        val out = run(
            listOf(
                c("1", "晴天 MV"),
                c("2", "夜曲 MV"), // 歌名未命中
                c("3", "晴天 MV", author = "路人甲"), // 歌手未命中
            ),
        )
        assertEquals(listOf("1"), out.map { it.candidate.id })
    }

    @Test
    fun `别名映射后歌手命中`() {
        val out = MvFilterChain.filter(
            listOf(c("1", "晴天 Jay Chou MV", author = "路人")),
            "晴天", "周杰伦",
            269_000L,
        )
        assertTrue(out.isNotEmpty()) // 标题含 Jay Chou → 命中
    }

    @Test
    fun `时长窗口边界与基准缺失`() {
        val out = run(listOf(c("1", "晴天 MV", durationSec = 263))) // -6s 超窗
        assertTrue(out.isEmpty())
        val out2 = run(listOf(c("2", "晴天 MV", durationSec = 309))) // +40s 边界内
        assertTrue(out2.isNotEmpty())
        assertTrue(run(listOf(c("3", "晴天 MV")), durationMs = null).isEmpty()) // 基准缺失丢弃
    }

    @Test
    fun `置信度排序官方与播放量优先`() {
        val out = run(
            listOf(
                c("normal", "晴天 MV", play = 1_000),
                c("official", "晴天 MV 官方", play = 5_000_000, official = true),
            ),
        )
        assertEquals("official", out.first().candidate.id)
        assertTrue(out.first().confidence >= 0.7f)
    }
}
