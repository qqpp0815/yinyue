package com.yinyue.player.data.fallback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * 抖音解析（用户报"兜底方案抖音链接解析失败"：
 * 官方的 _ROUTER_DATA/iteminfo 老结构都已失效，这里把各代结构都钉住）。
 */
class DouyinParserTest {

    private val itemJson = """
        {"desc":"看看【某某的作品】我的新歌 好听","author":{"nickname":"某某"},
         "music":{"play_addr":{"url_list":["https://sf.douyin.com/music-origin.m4s"]}},
         "video":{"play_addr":{"url_list":["https://www.douyin.com/aweme/v1/playwm/?video_id=abc"]}}}
    """.trimIndent()

    private fun renderPage(json: String, encode: Boolean): String {
        val payload = if (encode) URLEncoder.encode(json, "UTF-8") else json
        return """<html><head><title>抖音</title></head><body>
            <script id="RENDER_DATA" type="application/json">$payload</script>
            </body></html>"""
    }

    @Test
    fun `RENDER_DATA URI 编码结构能解析出音频与画面`() {
        val html = renderPage("""{"app":{"videoInfoRes":{"item_list":[$itemJson]}}}""", encode = true)
        val media = DouyinParser.parseShareHtml(html)
        assertEquals("https://sf.douyin.com/music-origin.m4s", media?.audioUrl)
        // playwm 必须替换为 play（去水印）
        assertEquals("https://www.douyin.com/aweme/v1/play/?video_id=abc", media?.videoUrl)
        assertEquals("某某", media?.author)
        assertTrue(media!!.title.startsWith("看看"))
    }

    @Test
    fun `RENDER_DATA 未编码 JSON 也能解析`() {
        val html = renderPage("""{"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[$itemJson]}}}}""", encode = false)
        val media = DouyinParser.parseShareHtml(html)
        assertEquals("https://sf.douyin.com/music-origin.m4s", media?.audioUrl)
    }

    @Test
    fun `顶层键名变化不影响解析（广度搜索条目）`() {
        // 新版分享页顶层键不再是固定的 app / video_(id)/page
        val html = renderPage("""{"whateverV2":{"nested":{"deep":{"item_list":[$itemJson]}}}}""", encode = true)
        assertEquals("https://sf.douyin.com/music-origin.m4s", DouyinParser.parseShareHtml(html)?.audioUrl)
    }

    @Test
    fun `旧版 ROUTER_DATA 结构仍兼容`() {
        val html = """<html><script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{
            "videoInfoRes":{"item_list":[$itemJson]}}}};</script></html>"""
        val media = DouyinParser.parseShareHtml(html)
        assertEquals("https://sf.douyin.com/music-origin.m4s", media?.audioUrl)
    }

    @Test
    fun `旧版 iteminfo 接口结构仍兼容`() {
        val body = """[{"aweme_id":"7301234567890123456","item":$itemJson}]"""
        val media = DouyinParser.parseItemInfo(body)
        assertEquals("https://sf.douyin.com/music-origin.m4s", media?.audioUrl)
    }

    @Test
    fun `只有 bit_rate 分档时取码率最高的视频`() {
        val item = """
            {"desc":"x","video":{"bit_rate":[
              {"bit_rate":500000,"play_addr":{"url_list":["https://v/low.mp4"]}},
              {"bit_rate":2400000,"play_addr":{"url_list":["https://v/high.mp4"]}}
            ]}}
        """.trimIndent()
        val media = DouyinParser.parseShareHtml(renderPage("""{"app":{"item_list":[$item]}}""", encode = false))
        assertEquals("https://v/high.mp4", media?.videoUrl)
        assertEquals("", media?.audioUrl) // 没有原声：由调用方把视频当音轨
    }

    @Test
    fun `风控验证页解析为 null 且不带数据标记`() {
        val html = """<html><body><script src="https://lf-security.bytegoofy.com/obj/security-secsdk/runtime.js"></script>
            <div>请完成验证</div></body></html>"""
        assertNull(DouyinParser.parseShareHtml(html))
        assertFalse(DouyinParser.hasRenderData(html))
        assertFalse(DouyinParser.hasRouterData(html))
    }

    @Test
    fun `是数据页但没有媒体字段时能被识别为结构变化`() {
        val html = renderPage("""{"app":{"videoInfoRes":{"item_list":[]}}}""", encode = true)
        assertNull(DouyinParser.parseShareHtml(html))
        assertTrue(DouyinParser.hasRenderData(html))
    }

    @Test
    fun `空响应与非法 JSON 不抛异常`() {
        assertNull(DouyinParser.parseShareHtml(""))
        assertNull(DouyinParser.parseShareHtml("<html></html>"))
        assertNull(DouyinParser.parseItemInfo(""))
        assertNull(DouyinParser.parseItemInfo("[]"))
        assertNull(DouyinParser.parseItemInfo("not json"))
    }

    // ── 解析中继（分享页被风控后的稳定路径）──

    @Test
    fun `中继地址支持多种分隔符并去重去尾斜杠`() {
        val setting = "https://a.com/api/, https://b.com/x\nhttps://a.com/api;  非地址  "
        assertEquals(listOf("https://a.com/api", "https://b.com/x"), DouyinParser.relayEndpoints(setting))
        assertEquals(emptyList<String>(), DouyinParser.relayEndpoints(""))
    }

    @Test
    fun `中继返回纯文本直链也能识别（极简自建契约）`() {
        val media = DouyinParser.parseRelayBody("https://sf.douyin.com/audio.m4s")
        assertEquals("https://sf.douyin.com/audio.m4s", media?.audioUrl)
        assertNull(media?.videoUrl)
    }

    @Test
    fun `中继返回标准 JSON 能识别`() {
        val media = DouyinParser.parseRelayBody(
            """{"audio_url":"https://a/music.m4s","video_url":"https://a/v.mp4","desc":"歌名","author":"歌手"}""",
        )
        assertEquals("https://a/music.m4s", media?.audioUrl)
        assertEquals("https://a/v.mp4", media?.videoUrl)
        assertEquals("歌名", media?.title)
        assertEquals("歌手", media?.author)
    }

    @Test
    fun `中继返回包一层 data 或数组都能识别`() {
        assertEquals(
            "https://a/music.m4s",
            DouyinParser.parseRelayBody("""{"data":{"url":"https://a/music.m4s"}}""")?.audioUrl,
        )
        assertEquals(
            "https://a/music.m4s",
            DouyinParser.parseRelayBody("""[{"play_url":"https://a/music.m4s"}]""")?.audioUrl,
        )
    }

    @Test
    fun `中继返回成熟解析器常见结构能识别媒体与歌名`() {
        val media = DouyinParser.parseRelayBody(
            """{"aweme_detail":{"desc":"相见恨晚","author":{"nickname":"彭佳慧"},""" +
                """"music":{"title":"相见恨晚","play_url":"https://a/audio.m4s"},""" +
                """"video":{"play_addr":"https://a/video.mp4"}}}""",
        )
        assertEquals("https://a/audio.m4s", media?.audioUrl)
        assertEquals("https://a/video.mp4", media?.videoUrl)
        assertEquals("相见恨晚", media?.title)
        assertEquals("彭佳慧", media?.author)
    }

    @Test
    fun `中继返回非媒体内容返回 null`() {
        assertNull(DouyinParser.parseRelayBody(""))
        assertNull(DouyinParser.parseRelayBody("not json"))
        assertNull(DouyinParser.parseRelayBody("""{"code":404,"msg":"not found"}"""))
    }
}
