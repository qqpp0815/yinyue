package com.yinyue.script

import java.security.MessageDigest

/**
 * 脚本更新判定（纯函数，便于单测）。
 *
 * 判定以**内容**为准：脚本作者常常改了内容但忘改 @version，
 * 只看版本号会漏更新；反之版本号变了内容没变也没必要替换。
 */
object ScriptUpdateDecider {

    sealed interface Decision {
        /** 远端与本地内容一致 */
        data class UpToDate(val version: String) : Decision

        /** 远端内容有变化（newName 取远端头注释，缺省沿用本地名） */
        data class HasUpdate(val newName: String?, val newVersion: String, val newText: String) : Decision

        /** 远端不是有效的洛雪脚本 */
        data class InvalidRemote(val reason: String) : Decision
    }

    fun decide(
        currentText: String,
        remoteText: String,
        currentVersion: String,
    ): Decision {
        if (currentText == remoteText || digest(currentText) == digest(remoteText)) {
            return Decision.UpToDate(currentVersion)
        }
        val header = try {
            LxScriptHeaderParser.parse(remoteText)
        } catch (e: Exception) {
            return Decision.InvalidRemote(e.message ?: "缺少脚本头注释")
        }
        return Decision.HasUpdate(
            newName = header.name,
            newVersion = header.version ?: "",
            newText = remoteText,
        )
    }

    fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
