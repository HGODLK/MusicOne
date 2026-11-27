package com.musicone

/** 只有真正换音源、换号或登录态变了才触发首页重载，普通的资料刷新不重播入场动画。 */
internal class HomeSessionRevision {
    private data class Identity(
        val source: MusicSource,
        val installed: Boolean,
        val accountId: String?,
        val sessionToken: String,
    )

    private var identity: Identity? = null
    private var revision = 0L

    fun update(session: PlatformSession, installed: Boolean): Long {
        // 这里的 credential 只是宿主按账号和版本拼出来的状态标识，不是音源包的私有凭证。
        val next = Identity(session.source, installed,
            session.account?.userId.takeIf { installed },
            session.credential.takeIf { installed }.orEmpty())
        if (identity != next) {
            identity = next
            revision++
        }
        return revision
    }
}
