package com.musicone

/** 记下正在等登录的音源，登录页关掉时核对一次，确实登上了再切换过去。 */
internal class PendingSourceLogin {
    private var source: MusicSource? = null

    fun begin(value: MusicSource) { source = value }

    fun settle(hasAccount: (MusicSource) -> Boolean): MusicSource? {
        val pending = source ?: return null
        source = null
        return pending.takeIf(hasAccount)
    }
}
