package com.yinyue.player.domain.play

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放队列（对齐落雪的"试听列表"）：音频项与 MV 项混排，用户自行选择加入哪一类。
 * 队列持久化：audioId 与 mvBvid 两类条目（内存态 + 由调用方按需落库）。
 */
object PlaybackQueue {

    sealed class Item {
        abstract val title: String
        abstract val artist: String

        /** 音频项（音源1）：指向 MusicEntity */
        data class Audio(val musicId: Long, override val title: String, override val artist: String) : Item()

        /** MV 项（音源2/兜底）：指向 B站 bvid 或本地文件 */
        data class Mv(
            val bvid: String,
            override val title: String,
            override val artist: String,
            val localAudioPath: String? = null,
        ) : Item()
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _index = MutableStateFlow(-1)

    /** 删除当前播放项时交由播放协调器决策：停止或接管下一首，避免 UI 与声音错位 */
    var onCurrentRemoved: ((Item) -> Unit)? = null
    val currentIndex: StateFlow<Int> = _index

    val current: Item?
        get() = _items.value.getOrNull(_index.value)

    /** 加入队列并播放（对应"点击搜索结果"） */
    fun playNow(item: Item) {
        val list = _items.value.toMutableList()
        val existing = list.indexOfFirst { sameItem(it, item) }
        if (existing >= 0) {
            _index.value = existing
        } else {
            list.add(item)
            _items.value = list
            _index.value = list.size - 1
        }
    }

    /**
     * 以整个列表为播放上下文并从 [startIndex] 开始（对应"在搜索结果 / 歌手 / 歌单 / 榜单里点某一首"）。
     * 这样"下一首 / 上一首"（按钮、左右滑动、播完自动续播）才按**列表顺序**走——
     * 此前点一首只把那一首放进队列，切歌自然无处可去。
     */
    fun playList(items: List<Item>, startIndex: Int) {
        if (items.isEmpty()) return
        val idx = startIndex.coerceIn(0, items.size - 1)
        _items.value = items
        _index.value = idx
    }

    /**
     * 把队列下标对齐到"正在播放的音频"。
     *
     * 修复（用户报）：从「播放列表弹层 / 我的-播放列表」直接点条目时只调 `play(musicId)`，
     * 若下标不同步，界面高亮与实际播放不一致，且**播完续播会从旧下标继续**
     * （第 4 首点第 10 首 → 第 10 首播完跳去第 5 首，而不是第 11 首）。
     * 这里统一在下标不对应时对齐；已对应则不动（同一首歌在队列里出现多次时尊重用户点的那一项）。
     */
    fun syncIndexToAudio(musicId: Long): Boolean {
        val list = _items.value
        val cur = list.getOrNull(_index.value)
        if (cur is Item.Audio && cur.musicId == musicId) return false
        val idx = list.indexOfFirst { it is Item.Audio && it.musicId == musicId }
        if (idx < 0) return false
        _index.value = idx
        return true
    }

    /** 同 [syncIndexToAudio]，MV 版（按 bvid 对齐） */
    fun syncIndexToMv(bvid: String): Boolean {
        val list = _items.value
        val cur = list.getOrNull(_index.value)
        if (cur is Item.Mv && cur.bvid == bvid) return false
        val idx = list.indexOfFirst { it is Item.Mv && it.bvid == bvid }
        if (idx < 0) return false
        _index.value = idx
        return true
    }

    /** 跳到指定下标并返回该项（切歌模式计算出的目标；越界返回 null） */
    fun jumpTo(index: Int): Item? {
        val list = _items.value
        if (index !in list.indices) return null
        _index.value = index
        return list[index]
    }

    /** 仅加入队列不播放（对应"添加到播放列表"菜单） */
    fun add(item: Item): Boolean {
        val list = _items.value.toMutableList()
        if (list.any { sameItem(it, item) }) return false
        list.add(item)
        _items.value = list
        return true
    }

    /** 下一首播放（插队到当前之后） */
    fun addNext(item: Item): Boolean {
        val list = _items.value.toMutableList()
        val existIdx = list.indexOfFirst { sameItem(it, item) }
        if (existIdx >= 0) {
            // 已存在则移到当前之后
            val moved = list.removeAt(existIdx)
            val insertAt = (_index.value + 1).coerceIn(0, list.size)
            list.add(insertAt, moved)
            _items.value = list
            _index.value = if (existIdx <= _index.value) _index.value - 1 else _index.value
            return true
        }
        val insertAt = (_index.value + 1).coerceIn(0, list.size)
        list.add(insertAt, item)
        _items.value = list
        return true
    }

    fun addAll(newItems: List<Item>) {
        val list = _items.value.toMutableList()
        var changed = false
        for (item in newItems) {
            if (list.none { sameItem(it, item) }) {
                list.add(item)
                changed = true
            }
        }
        if (changed) _items.value = list
    }

    fun removeAt(index: Int) {
        val list = _items.value.toMutableList()
        if (index !in list.indices) return
        val removed = list[index]
        val removedIsCurrent = index == _index.value
        list.removeAt(index)
        _items.value = list
        _index.value = when {
            list.isEmpty() -> -1
            index < _index.value -> _index.value - 1
            _index.value >= list.size -> list.size - 1
            else -> _index.value
        }
        if (removedIsCurrent) {
            onCurrentRemoved?.invoke(removed)
        }
    }

    fun clear() {
        _items.value = emptyList()
        _index.value = -1
    }

    fun next(): Item? {
        val list = _items.value
        if (list.isEmpty()) return null
        _index.value = (_index.value + 1) % list.size
        return list[_index.value]
    }

    fun previous(): Item? {
        val list = _items.value
        if (list.isEmpty()) return null
        _index.value = if (_index.value - 1 < 0) list.size - 1 else _index.value - 1
        return list[_index.value]
    }

    private fun sameItem(a: Item, b: Item): Boolean = when {
        a is Item.Audio && b is Item.Audio -> a.musicId == b.musicId
        a is Item.Mv && b is Item.Mv -> a.bvid == b.bvid
        else -> false
    }
}



