package com.musicone.source.kugou

/** 跟踪当前进行中的请求标识，只有对应的那次请求结束时才能清除 loading 状态，避免旧请求误清。 */
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
