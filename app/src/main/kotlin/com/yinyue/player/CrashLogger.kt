package com.yinyue.player

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘（v0.9.2）。
 *
 * 背景：用户反馈"打不开 app / 切歌闪退"，但手头没有设备日志，只能靠猜。
 * 这里装一个全局未捕获异常钩子，把堆栈写到 `filesDir/crash_last.txt`，
 * 设置页「崩溃日志」可直接查看并复制给开发者 —— 下次崩了就能定位，而不是继续猜。
 * 只保留**最后一次**崩溃（覆盖写），不占空间、不长期驻留。
 */
object CrashLogger {

    private const val FILE_NAME = "crash_last.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("时间: ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                    append('\n')
                    append("线程: ").append(thread.name).append('\n')
                    append("版本: ").append(BuildConfig.VERSION_NAME).append('\n')
                    append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                    append(android.os.Build.MODEL).append(" / Android ").append(android.os.Build.VERSION.RELEASE)
                    append('\n')
                    append("----------------------------------------\n")
                    append(Log.getStackTraceString(error))
                }
                File(app.filesDir, FILE_NAME).writeText(text)
                Log.e("YY-CRASH", text)
            }
            // 交回系统默认处理，保持原有行为（弹系统崩溃框/写系统日志）
            previous?.uncaughtException(thread, error)
        }
    }

    /** 读取上次崩溃日志；没有则 null */
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()?.ifBlank { null }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
