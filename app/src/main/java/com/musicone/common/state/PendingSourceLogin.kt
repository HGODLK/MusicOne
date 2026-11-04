package com.musicone

/** 记录等待登录结果的音源；登录页关闭时只结算一次，有账号才交给调用方切换。 */
internal class PendingSourceLogin {
    private var source: MusicSource? = null

    fun begin(value: MusicSource) { source = value }

    fun settle(hasAccount: (MusicSource) -> Boolean): MusicSource? {
        val pending = source ?: return null
        source = null
        return pending.takeIf(hasAccount)
    }
}
