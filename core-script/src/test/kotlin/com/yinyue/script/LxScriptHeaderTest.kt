package com.yinyue.script

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LxScriptHeaderTest {

    @Test
    fun `解析标准头注释`() {
        val script = """
            /**
             * @name 测试源
             * @description 一个测试脚本
             * @author 张三
             * @homepage https://example.com
             * @version 1.2.3
             */
            var x = 1;
        """.trimIndent()
        val h = LxScriptHeaderParser.parse(script)
        assertEquals("测试源", h.name)
        assertEquals("一个测试脚本", h.description)
        assertEquals("张三", h.author)
        assertEquals("https://example.com", h.homepage)
        assertEquals("1.2.3", h.version)
    }

    @Test
    fun `字段超长按INFO_NAMES截断`() {
        val longName = "x".repeat(30) // name 上限 24
        val script = "/**\n * @name $longName\n */\nvar x = 1;"
        val h = LxScriptHeaderParser.parse(script)
        assertEquals(24, h.name!!.length)
    }

    @Test
    fun `接受星号加感叹号形式的块注释`() {
        // 压缩器（如 webpack/terser）常用这种写法保留版权注释；落雪收藏夹里大部分脚本都是它。
        // 只认双星号会把它们全部拒掉（实测 15 个脚本里 9 个属于这种）。
        val script = "/*!\n * @name Free listen\n * @version v1.1.1\n */\nvar x = 1;"
        val h = LxScriptHeaderParser.parse(script)
        assertEquals("Free listen", h.name)
        assertEquals("v1.1.1", h.version)
    }

    @Test
    fun `容忍 UTF-8 BOM`() {
        val script = "\uFEFF/*!\n * @name 带BOM的源\n */\nvar x = 1;"
        assertEquals("带BOM的源", LxScriptHeaderParser.parse(script).name)
    }

    @Test
    fun `无 name 也可导入并按落雪契约留空`() {
        // 落雪对缺 @name 的脚本给默认名照常导入；此处返回 null，由调用方用兜底名
        val script = "/*\n * @description 只有描述\n */\nvar x = 1;"
        val h = LxScriptHeaderParser.parse(script)
        assertEquals(null, h.name)
        assertEquals("只有描述", h.description)
    }

    @Test
    fun `缺头注释报无效脚本`() {
        try {
            LxScriptHeaderParser.parse("var x = 1;")
            fail("应抛出 InvalidLxScriptException")
        } catch (e: InvalidLxScriptException) {
            assertEquals(true, e.message!!.contains("缺少头注释"))
        }
    }
}
