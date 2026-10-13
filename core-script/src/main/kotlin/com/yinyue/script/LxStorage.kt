package com.yinyue.script

/**
 * 脚本可见的受限持久化存储（v3.0 §3.2 要求的 localStorage）。
 *
 * 设计：core-script 是纯 JVM 模块、拿不到 Android Context，
 * 因此持久化由 App 层注入实现（如按脚本 id 落文件），这里只定义契约 + 内存兜底。
 * 每个脚本一个实例 → 天然"仅脚本自身可见"。
 */
interface LxStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun clear()
    fun keys(): List<String>
}

/** 默认实现：仅进程内有效（App 层未注入时使用，脚本仍能跑，只是重启后丢失） */
class LxMemoryStorage : LxStorage {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun clear() {
        map.clear()
    }

    override fun keys(): List<String> = map.keys.toList()
}
