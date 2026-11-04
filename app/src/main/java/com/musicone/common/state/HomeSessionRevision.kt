package com.musicone

/** 首页只在实际会话切换时重载，普通账号校验和资料更新不重播推荐入场。 */
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
        // 已运行音源的 credential 是宿主生成的公开会话版本标识，不是包内登录凭证。
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
