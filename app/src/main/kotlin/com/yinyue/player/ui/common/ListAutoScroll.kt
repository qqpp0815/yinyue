package com.yinyue.player.ui.common

import androidx.compose.foundation.lazy.LazyListState

/**
 * 把列表滚到"当前项可见且居中偏上"（用户要求：点中间位置的歌后打开列表，当前歌要看得见、方便上下滑）。
 *
 * 用"当前项上方保留 [rowsAbove] 行"的条目定位而不是像素偏移：
 * BottomSheet/弹层打开动画期间 viewport 尺寸在变，像素 offset 会算偏（实测当前项被推出视口下缘）；
 * 按条目数定位对任何行高、任何视口都稳。
 */
suspend fun LazyListState.scrollToCurrentCenter(index: Int, rowsAbove: Int = 2) {
    if (index < 0) return
    runCatching { animateScrollToItem((index - rowsAbove).coerceAtLeast(0)) }
}
