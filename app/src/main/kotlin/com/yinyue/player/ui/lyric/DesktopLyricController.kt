package com.yinyue.player.ui.lyric

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.yinyue.domain.lyric.LrcParser
import com.yinyue.player.data.prefs.SettingsRepository
import com.yinyue.player.player.PlayerController
import com.yinyue.player.ui.player.PlaybackCoordinatorHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 桌面歌词（桌面端置顶歌词窗口在 Android 上的对应实现：**悬浮窗歌词**）。
 *
 * 对照落雪 desktopLyric.* 的落地情况（Android 无意义的桌面窗口项不列）：
 *  - `enable` 开关、拖动移动、`isLock` 锁定（锁定后不可拖、隐藏控制按钮）
 *  - `pauseHide` **暂停/未在播放时隐藏**（默认开）
 *  - `style.fontSize` 字号、`style.opacity` 透明度、`style.lyricPlayedColor` 当前行高亮色、
 *    `style.lyricUnplayColor` 相邻行颜色
 *  - **多行显示**：当前行 + 上下各一行（对应落雪的多行滚动歌词）
 *  - `x/y` 位置记忆
 *  - 控制按钮：上一首 / 播放暂停 / 下一首（触屏无悬停，单击唤出；对应落雪悬停显示控制条）
 *
 * 未授予"显示在其他应用上层"权限时不显示（由设置页引导授权）。
 */
object DesktopLyricController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var style: DesktopLyricStyle = DesktopLyricStyle()
    private var appContext: Context? = null

    /** 歌词三行（多行模式）或一行（单行模式） */
    private var linePrev: TextView? = null
    private var lineCurrent: TextView? = null
    private var lineNext: TextView? = null
    private var controls: LinearLayout? = null
    private var actions: LinearLayout? = null
    private var playBtn: android.widget.ImageView? = null

    var isShowing: Boolean = false
        private set

    /**
     * 用户意图（对应持久化的"桌面歌词"开关），与 [isShowing]（窗口此刻是否真挂在屏幕上）区分开：
     * `pauseHide` 下"已开启但当前没在播放"是正常状态，此时 isShowing=false，
     * 若拿 isShowing 当开关状态，设置页会把"开"当成"关"写回持久化，开关自己跳回关闭。
     */
    var isEnabled: Boolean = false
        private set

    /** 悬浮窗权限 */
    fun canDraw(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun currentStyle(): DesktopLyricStyle = style

    /**
     * 用新样式重建窗口（样式改动后调用）。
     * 已开启时必须重建，即使此刻因 pauseHide 没挂在屏幕上——否则视图还是旧样式，
     * 等开始播放把它挂上去时看到的仍是改之前的样子。
     */
    fun applyStyle(context: Context, newStyle: DesktopLyricStyle) {
        style = newStyle
        if (!isEnabled) return
        hide()
        if (canDraw(context)) show(context) else isEnabled = true
    }

    fun show(context: Context): Boolean {
        if (isShowing) {
            isEnabled = true
            return true
        }
        if (!canDraw(context)) return false
        val app = context.applicationContext
        appContext = app
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val multi = style.multiLine
        val screenW = app.resources.displayMetrics.widthPixels
        val maxTextW = (screenW * 0.94f).toInt()
        val lineGapPx = dp(app, style.lineGapSp)

        fun lyricLine(sizeSp: Float, color: Int) = TextView(app).apply {
            setTextColor(color)
            textSize = sizeSp
            maxLines = 1
            // 落雪 style.ellipsis：关掉就让它溢出裁切（窗口宽度已按屏宽 94% 封顶，不会撑破屏幕）
            ellipsize = if (style.ellipsis) android.text.TextUtils.TruncateAt.END else null
            gravity = when (style.align) {
                "left" -> Gravity.START
                "right" -> Gravity.END
                else -> Gravity.CENTER
            }
            maxWidth = maxTextW
            if (style.shadowColor != 0) {
                // 落雪 style.lyricShadowColor：贴壁纸时保证可读性
                setShadowLayer(3f, 0f, dp(app, 1f).toFloat(), style.shadowColor)
            }
            this.text = ""
        }

        val zoom = if (style.zoomActive) 1.22f else 1f
        val prev = lyricLine(style.fontSizeSp * 0.82f, style.unplayedColor).apply { alpha = 0.55f }
        val current = lyricLine(style.fontSizeSp * zoom, style.playedColor).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val next = lyricLine(style.fontSizeSp * 0.82f, style.unplayedColor).apply { alpha = 0.55f }

        val lyricsColumn = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = when (style.align) {
                "left" -> Gravity.START
                "right" -> Gravity.END
                else -> Gravity.CENTER
            }
            setPadding(dp(app, 16), dp(app, 8), dp(app, 16), dp(app, 8))
            if (multi) {
                addView(prev)
                addView(current)
                addView(next)
            } else {
                addView(current)
            }
            // 行间距（落雪 style.lineGap）：给上下相邻行加垂直留白，当前行不加
            if (multi) {
                listOf(prev, next).forEach { line ->
                    (line.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.topMargin = lineGapPx / 2
                        it.bottomMargin = lineGapPx / 2
                        line.layoutParams = it
                    }
                }
            }
        }

        // 控制条（上一首/播放暂停/下一首）——单击歌词区域唤出，锁定状态下不显示
        val controlRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(app, 8), 0, dp(app, 6), 0)
            visibility = View.GONE
        }
        val btnPrev = iconButton(app, android.R.drawable.ic_media_previous, "上一首") {
            PlayerController.seekToPrevious()
        }
        val btnPlay = iconButton(app, android.R.drawable.ic_media_pause, "播放/暂停") {
            PlayerController.togglePlayPause()
        }
        val btnNext = iconButton(app, android.R.drawable.ic_media_next, "下一首") {
            PlayerController.seekToNext()
        }
        controlRow.addView(btnPrev)
        controlRow.addView(btnPlay)
        controlRow.addView(btnNext)

        // 锁定 / 关闭
        val actionRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(app, 4), 0)
            visibility = View.GONE
        }
        val btnClose = iconButton(app, android.R.drawable.ic_menu_close_clear_cancel, "关闭桌面歌词") {
            hide()
            scope.launch {
                runCatching { SettingsRepository.get(app).setDesktopLyric(false) }
            }
        }
        actionRow.addView(btnClose)

        val container = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(app, 20).toFloat()
                setColor(Color.parseColor("#CC000000"))
                setStroke(dp(app, 1), Color.parseColor("#33FFFFFF"))
            }
            alpha = style.opacity
            addView(controlRow)
            addView(lyricsColumn)
            addView(actionRow)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 只保留 NOT_FOCUSABLE：不要让窗口画到自身 frame 之外。
            // 实测 FLAG_LAYOUT_IN_SCREEN 会被系统补上 FLAG_LAYOUT_NO_LIMITS，
            // 导致"看得见但点不到"（触摸框与绘制区域不一致，点击穿透到下层应用）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = style.x ?: 0
            y = style.y ?: dp(app, 120)
        }

        // 拖动移动（锁定时不生效）；单击唤出控制条；拖动结束保存位置（对应落雪 x/y 记忆）
        container.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                android.util.Log.i("YY-LYRIC", "touch action=${event.action} at (${event.rawX}, ${event.rawY}) locked=${style.locked}")
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = lp.x; startY = lp.y
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (style.locked) return true
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(container, lp) }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved) {
                            // 位置记忆
                            style = style.copy(x = lp.x, y = lp.y)
                            scope.launch { runCatching { DesktopLyricStyle.save(app, style) } }
                            return true
                        }
                        // 单击：唤出/收起控制条（锁定时不显示）
                        if (style.locked) return true
                        val show = controlRow.visibility != View.VISIBLE
                        controlRow.visibility = if (show) View.VISIBLE else View.GONE
                        actionRow.visibility = if (show) View.VISIBLE else View.GONE
                        // 悬浮窗必须显式请求重排：只改子视图可见性时窗口不会自动 resize，
                        // 结果是"按钮加进去了但被裁在窗口外看不见"
                        runCatching { wm.updateViewLayout(container, lp) }
                        return true
                    }
                }
                return moved
            }
        })

        windowManager = wm
        root = container
        params = lp
        linePrev = prev
        lineCurrent = current
        lineNext = next
        controls = controlRow
        actions = actionRow
        playBtn = btnPlay
        isEnabled = true

        // 未在播放时**先不挂窗**：挂上再由循环摘掉会闪一下空窗（用户看到的"未在播放"悬浮窗）。
        // 视图与参数都已就绪，updateLoop 检测到真正开始播放时会自己挂上。
        if (!shouldHideNow()) {
            isShowing = runCatching { wm.addView(container, lp) }.isSuccess
        }

        startUpdateLoop(app)
        return true
    }

    /** pauseHide：暂停或没有歌曲时不该显示悬浮窗（对应落雪 desktopLyric.pauseHide） */
    private fun shouldHideNow(): Boolean {
        val np = PlaybackCoordinatorHolder.nowPlaying.value
        val hasSong = np.songName.isNotBlank() || np.mvMode
        return style.pauseHide && (!hasSong || !PlayerController.state.value.isPlaying)
    }

    private fun startUpdateLoop(app: Context) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val np = PlaybackCoordinatorHolder.nowPlaying.value
                val playing = PlayerController.state.value.isPlaying
                val hasSong = np.songName.isNotBlank() || np.mvMode

                // 落雪 desktopLyric.pauseHide：暂停或没有歌曲时不显示悬浮窗
                if (shouldHideNow()) {
                    if (isShowing) detachWindow()
                } else if (!isShowing && canDraw(app)) {
                    // 恢复播放且之前被隐藏 → 重新挂上（保持用户设定的样式与位置）
                    attachExistingWindow(app)
                }

                if (isShowing) {
                    val current = lineCurrent
                    val raw = np.lyricText
                    if (raw.isNullOrBlank()) {
                        // 无歌词：显示「歌名 - 歌手」，与落雪一致
                        linePrev?.text = ""
                        current?.text = if (hasSong) "${np.songName} - ${np.artistName}" else ""
                        lineNext?.text = ""
                    } else {
                        val lines = LrcParser.parse(raw).lines
                        val idx = LrcParser.findCurrentLine(lines, PlayerController.position.value + np.lyricOffsetMs)
                        if (idx < 0) {
                            linePrev?.text = ""
                            current?.text = "…"
                            lineNext?.text = lines.firstOrNull()?.text ?: ""
                        } else {
                            linePrev?.text = lines.getOrNull(idx - 1)?.text ?: ""
                            current?.text = lines.getOrNull(idx)?.text ?: ""
                            lineNext?.text = lines.getOrNull(idx + 1)?.text ?: ""
                        }
                    }
                    playBtn?.setImageResource(
                        if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    )
                }
                delay(400)
            }
        }
    }

    /** 从窗口移除但保留对象（pauseHide 用），再次播放时可直接挂回 */
    private fun detachWindow() {
        val wm = windowManager
        val view = root
        if (wm != null && view != null) runCatching { wm.removeView(view) }
        isShowing = false
    }

    private fun attachExistingWindow(app: Context) {
        val wm = windowManager ?: (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val view = root ?: return
        val lp = params ?: return
        // addView 失败（如权限被撤销）时必须如实保持 isShowing=false，
        // 否则循环以为已挂上，再也不会重试，窗口就永远不出现
        isShowing = runCatching { wm.addView(view, lp) }.isSuccess
        windowManager = wm
    }

    fun hide() {
        job?.cancel()
        job = null
        isEnabled = false
        detachWindow()
        windowManager = null
        root = null
        params = null
        linePrev = null
        lineCurrent = null
        lineNext = null
        controls = null
        actions = null
        playBtn = null
    }

    /** 悬浮窗内的小图标按钮（系统内置图标；触屏靠单击唤出控制条） */
    private fun iconButton(
        context: Context,
        iconRes: Int,
        description: String,
        onClick: () -> Unit,
    ): android.widget.ImageView = android.widget.ImageView(context).apply {
        setImageResource(iconRes)
        contentDescription = description
        setColorFilter(Color.WHITE)
        val size = dp(context, 34)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginStart = dp(context, 2)
            marginEnd = dp(context, 2)
        }
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        setOnClickListener { onClick() }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
