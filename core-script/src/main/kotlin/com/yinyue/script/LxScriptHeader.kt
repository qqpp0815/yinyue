package com.yinyue.script

/**
 * 洛雪脚本头注释解析，对齐 lx-music-desktop userApi/utils.ts 的 INFO_NAMES 规则：
 * 脚本必须以块注释开头，用 "@key value" 逐行声明，字段超长截断。
 */
object LxScriptHeaderParser {

    private val MAX_LENGTHS = linkedMapOf(
        "name" to 24,
        "description" to 36,
        "author" to 56,
        "homepage" to 1024,
        "version" to 36,
    )
    private val LINE_RX = Regex("""^\s?\*\s?@(\w+)\s(.+)$""")

    /**
     * 解析头注释。
     *
     * 与落雪（userApi/utils.ts 的 parseScriptInfo）保持一致：只要求文件以块注释开头且注释闭合，
     * **不要求 @name**（缺失时调用方用兜底名）。
     *
     * 关键兼容点：必须同时接受「星号后紧跟感叹号」的块注释写法（压缩器用来保留版权注释的常见形式）。
     * 落雪收藏夹里 15 个脚本中有 9 个用的就是这种写法，只认双星号会把它们全部拒掉。
     * 另外这里容忍 UTF-8 BOM —— 落雪在带 BOM 的文件上也会失败，本实现更宽松。
     */
    fun parse(script: String): LxScriptHeader {
        val text = script.removePrefix("\uFEFF").trimStart()
        if (!text.startsWith("/*")) {
            throw InvalidLxScriptException("无效的自定义源文件：缺少头注释（文件需以 /* 开头的注释块声明 @name 等信息）")
        }
        val blockEnd = text.indexOf("*/")
        if (blockEnd < 0) {
            throw InvalidLxScriptException("无效的自定义源文件：头注释未闭合")
        }
        val block = text.substring(0, blockEnd)
        val found = linkedMapOf<String, String>()
        for (line in block.lines()) {
            val m = LINE_RX.matchEntire(line) ?: continue
            val key = m.groupValues[1]
            val max = MAX_LENGTHS[key] ?: continue
            if (!found.containsKey(key)) {
                found[key] = m.groupValues[2].trim().take(max)
            }
        }
        return LxScriptHeader(
            name = found["name"],
            description = found["description"],
            author = found["author"],
            homepage = found["homepage"],
            version = found["version"],
        )
    }
}

data class LxScriptHeader(
    val name: String?,
    val description: String?,
    val author: String?,
    val homepage: String?,
    val version: String?,
)

class InvalidLxScriptException(message: String) : Exception(message)
