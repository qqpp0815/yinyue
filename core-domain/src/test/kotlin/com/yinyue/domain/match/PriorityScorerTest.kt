package com.yinyue.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 优先级算法测试：对齐 v1.0 §5.2 公式与规则3 的四级序 */
class PriorityScorerTest {

    @Test
    fun `打分公式 原唱x100 加 音质x10 加 来源权重`() {
        // 原唱无损（音源1）：1*100 + 4*10 + 1 = 141
        assertEquals(141, PriorityScorer.score(4, PriorityScorer.ORIGINAL, "kw"))
        // 原唱320K：100 + 30 + 1 = 131
        assertEquals(131, PriorityScorer.score(3, PriorityScorer.ORIGINAL, "wy"))
        // 翻唱无损：0 + 40 + 1 = 41（低于任何原唱档位）
        assertEquals(41, PriorityScorer.score(4, PriorityScorer.COVER, "kw"))
        // 未知原唱128K：-100 + 20 + 1 = -79（未知排最后）
        assertEquals(-79, PriorityScorer.score(2, PriorityScorer.UNKNOWN_ORIGIN, "kg"))
        // MV 音轨（来源权重 0）原唱无损：100 + 40 + 0 = 140
        assertEquals(140, PriorityScorer.score(4, PriorityScorer.ORIGINAL, "bilibili"))
    }

    @Test
    fun `四级优先级序 原唱高音质 大于 原唱低音质 大于 翻唱高音质 大于 翻唱低音质`() {
        val originalHq = PriorityScorer.score(4, PriorityScorer.ORIGINAL, "kw")   // 100+40+1 = 141
        val originalLq = PriorityScorer.score(2, PriorityScorer.ORIGINAL, "kw")   // 100+20+1 = 121
        val coverHq = PriorityScorer.score(4, PriorityScorer.COVER, "kw")         // 0+40+1 = 41
        val coverLq = PriorityScorer.score(2, PriorityScorer.COVER, "kw")         // 0+20+1 = 21
        assertTrue(originalHq > originalLq)
        assertTrue(originalLq > coverHq)   // 关键：原唱低音质 必须高于 翻唱高音质（旧公式做不到）
        assertTrue(coverHq > coverLq)
        // 官方四级序按"先原唱后音质"，由 tier 标签体现
        assertEquals("原唱高音质", PriorityScorer.tier(PriorityScorer.ORIGINAL, 4))
        assertEquals("原唱低音质", PriorityScorer.tier(PriorityScorer.ORIGINAL, 2))
        assertEquals("翻唱高音质", PriorityScorer.tier(PriorityScorer.COVER, 4))
        assertEquals("翻唱低音质", PriorityScorer.tier(PriorityScorer.COVER, 2))
    }

    @Test
    fun `排序键 分数优先 再脚本优先级 再响应时间`() {
        data class L(val id: String, val q: Int, val o: Int, val p: String, val sp: Int, val rt: Long?)

        val cmp = PriorityScorer.comparator<L>(
            qualityOf = { it.q },
            originalOf = { it.o },
            platformOf = { it.p },
            scriptPriorityOf = { it.sp },
            responseTimeOf = { it.rt },
        )
        val list = listOf(
            L("low", q = 2, o = PriorityScorer.UNKNOWN_ORIGIN, p = "kw", sp = 9, rt = 100),
            L("hqSlow", q = 4, o = PriorityScorer.ORIGINAL, p = "kw", sp = 1, rt = 900),
            L("hqFast", q = 4, o = PriorityScorer.ORIGINAL, p = "kw", sp = 5, rt = 500),
            L("mid", q = 3, o = PriorityScorer.ORIGINAL, p = "wy", sp = 9, rt = 200),
        )
        val sorted = list.sortedWith(cmp).map { it.id }
        // 分最高的是 hqSlow/hqFast（同分），脚本优先级 5 > 1 → hqFast 在前
        assertEquals(listOf("hqFast", "hqSlow", "mid", "low"), sorted)
    }

    @Test
    fun `来源权重 音源1平台为1 其他为0`() {
        assertEquals(1, PriorityScorer.sourceWeight("kw"))
        assertEquals(1, PriorityScorer.sourceWeight("wy"))
        assertEquals(0, PriorityScorer.sourceWeight("bilibili"))
        assertEquals(0, PriorityScorer.sourceWeight("local"))
        assertEquals(0, PriorityScorer.sourceWeight("douyin_parse"))
    }

    @Test
    fun `新增平台按注册表自动获得音源1权重`() {
        // 模拟：注册表里有 kw 与新增平台 newfm，没有 bilibili
        val registered = setOf("kw", "wy", "newfm")
        val weight: (String) -> Int = { p -> PriorityScorer.audioSourceWeight(p) { it in registered } }
        assertEquals(1, weight("newfm")) // 新增平台自动按音源1 参与排序
        assertEquals(1, weight("kw"))
        assertEquals(0, weight("bilibili")) // MV 音轨仍为 0
        // 注册表判定：新平台得分 = 内置平台同规格得分（而不是被当作 0 权重）
        assertEquals(
            PriorityScorer.score(4, PriorityScorer.ORIGINAL, "kw"),
            PriorityScorer.score(4, PriorityScorer.ORIGINAL, sourceWeight = weight("newfm")),
        )
    }

    @Test
    fun `源分档遵循手动大于本地大于在线自动`() {
        // 用户规则：兜底导入（手动）优先级最高，其次本地缓存/下载，最后在线自动（脚本取链/B站匹配）
        assertEquals(
            PriorityScorer.TIER_MANUAL,
            PriorityScorer.sourceTier(isManualImport = true, hasLocalFile = true),
        )
        assertEquals(
            PriorityScorer.TIER_MANUAL,
            PriorityScorer.sourceTier(isManualImport = true, hasLocalFile = false),
        )
        assertEquals(
            PriorityScorer.TIER_LOCAL,
            PriorityScorer.sourceTier(isManualImport = false, hasLocalFile = true),
        )
        assertEquals(
            PriorityScorer.TIER_AUTO,
            PriorityScorer.sourceTier(isManualImport = false, hasLocalFile = false),
        )
        assertTrue(PriorityScorer.TIER_MANUAL > PriorityScorer.TIER_LOCAL)
        assertTrue(PriorityScorer.TIER_LOCAL > PriorityScorer.TIER_AUTO)
    }
}
