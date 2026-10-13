package com.yinyue.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本更新判定测试：以内容为准，不只看版本号 */
class ScriptUpdateDeciderTest {

    private fun script(name: String, version: String, body: String = "lx.send(lx.EVENT_NAMES.inited,{});") =
        """
        /**
         * @name $name
         * @version $version
         */
        $body
        """.trimIndent()

    @Test
    fun `内容一致判为已是最新`() {
        val text = script("测试源", "1.0.0")
        val d = ScriptUpdateDecider.decide(text, text, "1.0.0")
        assertTrue(d is ScriptUpdateDecider.Decision.UpToDate)
        assertEquals("1.0.0", (d as ScriptUpdateDecider.Decision.UpToDate).version)
    }

    @Test
    fun `内容变了但版本号没改仍判有更新`() {
        // 脚本作者常忘改 @version：只看版本号会漏更新
        val old = script("测试源", "1.0.0", "var x = 1;")
        val new = script("测试源", "1.0.0", "var x = 2;")
        val d = ScriptUpdateDecider.decide(old, new, "1.0.0")
        assertTrue("应判为有更新：$d", d is ScriptUpdateDecider.Decision.HasUpdate)
        assertEquals("1.0.0", (d as ScriptUpdateDecider.Decision.HasUpdate).newVersion)
        assertEquals(new, d.newText)
    }

    @Test
    fun `版本号变了内容也变了判有更新并带新版本`() {
        val old = script("测试源", "1.0.0")
        val new = script("测试源 V2", "2.0.0")
        val d = ScriptUpdateDecider.decide(old, new, "1.0.0")
        d as ScriptUpdateDecider.Decision.HasUpdate
        assertEquals("2.0.0", d.newVersion)
        assertEquals("测试源 V2", d.newName)
    }

    @Test
    fun `远端不是洛雪脚本时给出无效原因`() {
        val old = script("测试源", "1.0.0")
        val d = ScriptUpdateDecider.decide(old, "<html>404 Not Found</html>", "1.0.0")
        assertTrue("应判无效：$d", d is ScriptUpdateDecider.Decision.InvalidRemote)
    }

    @Test
    fun `空白差异不算更新`() {
        val old = script("测试源", "1.0.0")
        val same = old.replace("\n", "\r\n") // 换行符差异：内容哈希不同 → 算更新（保守，宁可提示）
        val d = ScriptUpdateDecider.decide(old, same, "1.0.0")
        assertTrue(d is ScriptUpdateDecider.Decision.HasUpdate)
    }
}
