package com.musicone.demo

/** 刷新期限不等于会员到期；失败保留已确认结果，服务端明确变更才替换。由权益层持锁访问。 */
internal class QqMembershipCache {
    var member: QqMembership? = null; private set
    var loading = false; private set
    var retryAtMs = 0L; private set

    fun current(nowMs: Long): QqMembership? = member?.effectiveAt(nowMs)
    fun begin(nowMs: Long): Boolean {
        if (loading || retryAtMs > nowMs || (member?.validUntilMs ?: 0L) > nowMs) return false
        loading = true
        return true
    }

    fun complete(result: QqMembership?, nowMs: Long) {
        loading = false
        remember(result, nowMs)
    }

    fun remember(result: QqMembership?, nowMs: Long) {
        if (result != null) member = result
        retryAtMs = if (result == null) nowMs + 30_000L else 0L
    }

    fun resume() { retryAtMs = 0L }
    fun nextRefreshMs(nowMs: Long): Long = maxOf(member?.validUntilMs ?: nowMs, retryAtMs)
}
