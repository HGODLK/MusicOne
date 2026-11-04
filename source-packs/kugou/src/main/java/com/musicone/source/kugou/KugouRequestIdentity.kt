package com.musicone.source.kugou

/** 取消和换号也需收尾；只有同一请求才能清除自己的加载状态。 */
internal class KugouRequestIdentity {
    private var sequence = 0L
    private val active = mutableMapOf<String, Long>()
    fun begin(key: String): Long = (++sequence).also { active[key] = it }
    fun current(key: String, token: Long): Boolean = active[key] == token
    fun finish(key: String, token: Long): Boolean = if (current(key, token)) {
        active.remove(key)
        true
    } else false
    fun cancel(key: String): Boolean = active.remove(key) != null
}
