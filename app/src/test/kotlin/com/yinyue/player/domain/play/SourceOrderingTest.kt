package com.yinyue.player.domain.play

import com.yinyue.player.data.db.entity.AudioLink
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 链接尝试顺序：外置脚本优先级 > 手动 > 本地 > 在线 > 优先级分。
 * 用户要求"切源要按外置音源优先级来"，这条顺序就是它的落地口径。
 */
class SourceOrderingTest {

    private fun link(
        id: Long,
        platform: String,
        score: Int = 30,
        scriptPriority: Int = 0,
        localPath: String? = null,
        responseMs: Long? = 100,
    ) = AudioLink(
        id = id,
        musicId = 1,
        scriptId = "s",
        platform = platform,
        remoteTrackId = "t$id",
        priorityScore = score,
        scriptPriority = scriptPriority,
        responseTimeMs = responseMs,
        localPath = localPath,
    )

    private val noLocal: (AudioLink) -> Boolean = { false }

    @Test
    fun `脚本优先级高的平台先尝试`() {
        // 用户把 kg 的脚本上移（priority 9），kw 脚本靠后（priority 1）
        val links = listOf(link(1, "kw", score = 40), link(2, "kg", score = 20))
        val ordered = SourceOrdering.ordered(links, mapOf("kg" to 9, "kw" to 1), noLocal)
        assertEquals(listOf("kg", "kw"), ordered.map { it.platform })
    }

    @Test
    fun `无脚本优先级差异时回落优先级分`() {
        val links = listOf(link(1, "kw", score = 20), link(2, "mg", score = 40))
        val ordered = SourceOrdering.ordered(links, mapOf("kw" to 0, "mg" to 0), noLocal)
        assertEquals(listOf("mg", "kw"), ordered.map { it.platform })
    }

    @Test
    fun `手动导入与本地文件优先于在线自动`() {
        val links = listOf(
            link(1, "kw", score = 43),                 // 在线自动、最高优先级分
            link(2, "kg", score = 10, localPath = "/tmp/a.mp3"), // 本地缓存
            link(3, "local", score = 1, localPath = "/tmp/b.mp3"), // 手动导入
        )
        val ordered = SourceOrdering.ordered(
            links,
            mapOf("kw" to 5, "kg" to 5, "local" to 5),
            localFileExists = { it.localPath != null },
        )
        assertEquals(listOf("local", "kg", "kw"), ordered.map { it.platform })
    }

    @Test
    fun `同一平台多条链接按优先级分与响应时间排序`() {
        val links = listOf(
            link(1, "kw", score = 20, responseMs = 500),
            link(2, "kw", score = 40, responseMs = 900),
            link(3, "kw", score = 20, responseMs = 100),
        )
        val ordered = SourceOrdering.ordered(links, mapOf("kw" to 1), noLocal)
        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
    }
}
