package com.yinyue.player.ui.lyric

import android.content.Context
import com.google.gson.Gson
import com.yinyue.player.data.prefs.SettingsRepository

/**
 * 桌面歌词（悬浮窗歌词）样式与行为，对应落雪 `desktopLyric.*` 一组设置。
 * 只保留 Android 有意义的项；桌面窗口专属项（多显示器/任务栏/全屏检测/鼠标滚轮）不适用。
 */
data class DesktopLyricStyle(
    /** 字号（sp），对应 desktopLyric.style.fontSize（落雪默认 20） */
    val fontSizeSp: Float = 20f,
    /** 整体不透明度 0.2~1.0，对应 style.opacity（落雪默认 95%） */
    val opacity: Float = 0.95f,
    /** 当前行高亮色，对应 style.lyricPlayedColor */
    val playedColor: Int = 0xFFFF5C5C.toInt(),
    /** 未播放行颜色，对应 style.lyricUnplayColor */
    val unplayedColor: Int = 0xFFE8E8E8.toInt(),
    /** 单行 / 多行（当前行 + 上下相邻行，对应落雪的滚动多行显示） */
    val multiLine: Boolean = true,
    /** 暂停或未在播放时隐藏，对应 desktopLyric.pauseHide */
    val pauseHide: Boolean = true,
    /** 锁定：不响应拖动、隐藏控制按钮，对应 desktopLyric.isLock */
    val locked: Boolean = false,
    /** 上次拖动位置（对应 desktopLyric.x/y 记忆） */
    val x: Int? = null,
    val y: Int? = null,
    /** 行间距（sp），对应 style.lineGap（落雪默认 15） */
    val lineGapSp: Float = 15f,
    /** 文字阴影色，对应 style.lyricShadowColor（落雪默认 rgba(0,0,0,0.18)；全透明=不画阴影） */
    val shadowColor: Int = 0x2E000000,
    /** 当前行放大，对应 style.isZoomActiveLrc */
    val zoomActive: Boolean = false,
    /** 歌词水平对齐，对应 style.align（left / center / right） */
    val align: String = "center",
    /** 超长省略号，对应 style.ellipsis */
    val ellipsis: Boolean = false,
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        private const val KEY = "desktop_lyric_style"

        fun parse(json: String?): DesktopLyricStyle =
            if (json.isNullOrBlank()) DesktopLyricStyle()
            else runCatching { Gson().fromJson(json, DesktopLyricStyle::class.java) }
                .getOrDefault(DesktopLyricStyle())

        suspend fun load(context: Context): DesktopLyricStyle =
            parse(SettingsRepository.get(context).desktopLyricStyle())

        suspend fun save(context: Context, style: DesktopLyricStyle) {
            SettingsRepository.get(context).setDesktopLyricStyle(style.toJson())
        }

        fun key(): String = KEY
    }
}
