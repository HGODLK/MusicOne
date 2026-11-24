package com.musicone

/** 成功才开始长周期计时；失败只等短间隔，避免一次网络波动耽误数小时。 */
internal class SourceAccountRefreshSchedule(
    private val validMs: Long = 6 * 60 * 60 * 1000L,
    private val retryMs: Long = 5 * 60 * 1000L,
) {
    private val attemptedAt = mutableMapOf<String, Long>()
    private val succeededAt = mutableMapOf<String, Long>()

    /** expired：音源已报告登录失效，此时不等长周期，按短间隔复核，避免卡在失效直到重启。 */
    fun due(id: String, now: Long, expired: Boolean = false): Boolean {
        if (attemptedAt[id]?.let { now - it < retryMs } == true) return false
        if (expired) return true
        return succeededAt[id]?.let { now - it >= validMs } ?: true
    }

    fun attempted(id: String, now: Long) { attemptedAt[id] = now }

    fun succeeded(id: String, now: Long) { succeededAt[id] = now }
}
