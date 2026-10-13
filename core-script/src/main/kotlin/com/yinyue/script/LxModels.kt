package com.yinyue.script

/** lx 脚本规范（API 2.0.0）事件名，对齐 lx-music-desktop preload.js */
object LxEventNames {
    const val REQUEST = "request"
    const val INITED = "inited"
    const val UPDATE_ALERT = "updateAlert"
}

/** 允许的源白名单（kw/kg/tx/wy/mg/xm/local）与音质白名单，对齐 preload.js 的 allSources/supportActions */
object LxSpec {
    val ALLOWED_SOURCES = setOf("kw", "kg", "tx", "wy", "mg", "xm", "local")
    val ALLOWED_QUALITYS = listOf("128k", "320k", "flac", "flac24bit")
    fun allowedActions(source: String): Set<String> =
        if (source == "local") setOf("musicUrl", "lyric", "pic") else setOf("musicUrl")
}

data class LxSourceInfo(
    val name: String,
    val type: String,
    val actions: List<String>,
    val qualitys: List<String>,
)

data class LxInitedPayload(
    val openDevTools: Boolean?,
    val sources: Map<String, LxSourceInfo>,
)

/** 暴露给脚本的 lx.currentScriptInfo（对齐 preload.js，含 rawScript：部分脚本会读自身源码做自更新） */
data class LxCurrentScriptInfo(
    val name: String = "user_api_unknown",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
    val env: String = "mobile",
    val rawScript: String = "",
)

/** 脚本请求失败（返回非法/脚本异常/域名不允许等） */
class LxScriptRequestException(message: String) : Exception(message)

/** 脚本请求超时（v3.0：8s 默认） */
class LxScriptTimeoutException(message: String) : Exception(message)
