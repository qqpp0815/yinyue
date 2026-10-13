package com.yinyue.player.data.fallback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抖音分享文案解析（用户报"无法从分享链接解析出视频 ID"，这里把各种形态钉住） */
class DouyinShareTest {

    @Test
    fun `从整段分享文案里取出链接`() {
        val text = "7.43 abc:/ 复制打开抖音，看看【某某某的作品】我的新歌  https://v.douyin.com/iRNBho6/ 复制此链接，打开Dou音搜索"
        assertEquals("https://v.douyin.com/iRNBho6/", DouyinShare.extractUrl(text))
    }

    @Test
    fun `提取 Markdown 包裹的新分享链接`() {
        val text = "6.43 复制打开抖音 [ https://v.douyin.com/dOTksL5I-dM/](< https://v.douyin.com/dOTksL5I-dM/>) eOK:/"
        assertEquals("https://v.douyin.com/dOTksL5I-dM/", DouyinShare.extractUrl(text))
    }

    @Test
    fun `提取未闭合方括号的新分享链接`() {
        val text = "8.71 复制打开抖音，看看【一人一首成名曲的作品】# 回忆杀 # 彭佳慧 # 相见恨晚 # 音乐推荐...[ https://v.douyin.com/X-PUcUsLdK8/](< https://v.douyin.com/X-PUcUsLdK8/>)[ q@e.od](链接) :1pm pqr:/ 09/11"
        assertEquals("https://v.douyin.com/X-PUcUsLdK8/", DouyinShare.extractUrl(text))
    }

    @Test
    fun `提取分享文案书名号歌名`() {
        val text = "6.43 复制打开抖音，看看【短视频学习的作品】# 上热门 # 经典歌曲分享 《梦醒时分》 [ https://v.douyin.com/dOTksL5I-dM/]"
        assertEquals("梦醒时分", DouyinShare.extractTitle(text))
    }

    @Test
    fun `剥掉链接尾随的中文标点`() {
        val text = "看这个 https://www.douyin.com/video/7301234567890123456，很有意思"
        assertEquals("https://www.douyin.com/video/7301234567890123456", DouyinShare.extractUrl(text))
    }

    @Test
    fun `短链判定`() {
        assertTrue(DouyinShare.isShortLink("https://v.douyin.com/iRNBho6/"))
        assertFalse(DouyinShare.isShortLink("https://www.douyin.com/video/7301234567890123456"))
        assertFalse(DouyinShare.isShortLink("https://www.iesdouyin.com/share/video/7301234567890123456/"))
    }

    @Test
    fun `长链直接解析出 id`() {
        assertEquals(
            "7301234567890123456",
            DouyinShare.parseId("https://www.douyin.com/video/7301234567890123456"),
        )
        assertEquals(
            "7301234567890123456",
            DouyinShare.parseId("https://www.iesdouyin.com/share/video/7301234567890123456/?region=CN"),
        )
        assertEquals(
            "7301234567890123456",
            DouyinShare.parseId("https://www.douyin.com/note/7301234567890123456"),
        )
    }

    @Test
    fun `短链跳转后的最终地址能解析出 id`() {
        // 短链本身没有 id（修复前正是在这里报"无法解析视频 ID"）
        assertEquals(null, DouyinShare.parseId("https://v.douyin.com/iRNBho6/"))
        // 跟随跳转后的最终地址
        assertEquals(
            "7301234567890123456",
            DouyinShare.parseId("https://www.douyin.com/video/7301234567890123456?previous_page=app_code_link"),
        )
    }

    @Test
    fun `查询参数形态的 id`() {
        assertEquals(
            "7301234567890123456",
            DouyinShare.parseId("https://www.douyin.com/user/xxx?modal_id=7301234567890123456"),
        )
        assertEquals(
            "7301234567890123456",
            DouyinShare.parseId("https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=7301234567890123456"),
        )
    }

    @Test
    fun `文案里没有链接时返回 null`() {
        assertEquals(null, DouyinShare.extractUrl("复制打开抖音，看看作品"))
    }
}
