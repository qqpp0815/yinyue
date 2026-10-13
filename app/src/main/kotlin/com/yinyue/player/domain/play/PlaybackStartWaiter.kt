package com.yinyue.player.domain.play

import com.yinyue.player.player.PlayerController
import kotlinx.coroutines.delay

/**
 * Playback start module：判定一次 playUrl/playMv 是否真实起播。
 * interface 只暴露 [wait]；epoch 绑定、错误归属、超时降级判定都是 implementation。
 */
object PlaybackStartWaiter {
    const val DEFAULT_TIMEOUT_MS = 2_500L
    const val POLL_MS = 50L

    suspend fun wait(epoch: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val attemptStartMs = System.currentTimeMillis()
        PlayerController.clearLastError()
        val deadline = attemptStartMs + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlayerController.lastError.value?.let { error ->
                if (error.epoch == epoch && error.atMs >= attemptStartMs) return false
            }
            val state = PlayerController.state.value
            if (state.epoch == epoch && (
                    state.isPlaying || state.bufferedPositionMs > 0 ||
                        (!state.buffering && state.durationMs > 0)
                    )
            ) {
                return true
            }
            delay(POLL_MS)
        }
        val state = PlayerController.state.value
        android.util.Log.i(
            "YY-SPEED",
            "起播等待超时：epoch=${state.epoch}/$epoch playing=${state.isPlaying} buffering=${state.buffering} " +
                "buffered=${state.bufferedPositionMs} dur=${state.durationMs} pos=${PlayerController.position.value}",
        )
        return state.epoch == epoch && (
            state.isPlaying || PlayerController.position.value > 0 || state.bufferedPositionMs > 0
            )
    }
}
