package com.yinyue.player.player.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏/手动导入的缓存保护集（v3.0 §8「收藏实体的缓存不参与 LRU 淘汰」）。
 *
 * 回归的 bug：`protect("fav:<id>")` 把字符串 `fav:42` 塞进保护集，而淘汰器比较的是**缓存 span 的 key**（播放 URL），
 * 两者永远不可能相等 —— 收藏保护形同虚设（设置页照样能看到收藏，缓存照样被 LRU 淘汰）。
 * 修法：`fav:<id>` / `manual:<id>:<name>` 解析成 musicId，按实体把它已注册的 URL 纳入保护。
 */
class MusicCacheProtectionTest {

    @Test
    fun favProtectionCoversAlreadyRegisteredUrls() {
        val url = "https://cdn.example.com/song-1.m4s"
        MusicCache.registerKey(1L, url)
        assertFalse("未收藏时不应在保护集里", MusicCache.protectedKeysSnapshot().contains(url))

        MusicCache.protect("fav:1", true)
        assertTrue("收藏后该实体的缓存 key 必须进入保护集", MusicCache.protectedKeysSnapshot().contains(url))

        MusicCache.protect("fav:1", false)
        assertFalse("取消收藏后应移出保护集", MusicCache.protectedKeysSnapshot().contains(url))
    }

    @Test
    fun favProtectionCoversUrlsRegisteredLater() {
        val url = "https://cdn.example.com/song-2.m4s"
        MusicCache.protect("fav:2", true)
        // 先收藏后播放：注册时就必须带上保护，否则首次缓存仍会被淘汰
        MusicCache.registerKey(2L, url)
        assertTrue(MusicCache.protectedKeysSnapshot().contains(url))

        MusicCache.protect("fav:2", false)
        assertFalse(MusicCache.protectedKeysSnapshot().contains(url))
    }

    @Test
    fun manualImportProtectionUsesSameEntitySemantics() {
        val url = "https://cdn.example.com/song-3.m4s"
        MusicCache.registerKey(3L, url)
        MusicCache.protect("manual:3:周杰伦 - 晴天.mp3", true)
        assertTrue(MusicCache.protectedKeysSnapshot().contains(url))
        MusicCache.protect("manual:3:周杰伦 - 晴天.mp3", false)
        assertFalse(MusicCache.protectedKeysSnapshot().contains(url))
    }

    @Test
    fun literalKeyStillSupported() {
        MusicCache.protect("https://cdn.example.com/raw.m4s", true)
        assertTrue(MusicCache.protectedKeysSnapshot().contains("https://cdn.example.com/raw.m4s"))
        MusicCache.protect("https://cdn.example.com/raw.m4s", false)
    }
}
