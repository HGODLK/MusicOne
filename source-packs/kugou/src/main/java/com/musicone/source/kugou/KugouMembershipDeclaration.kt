package com.musicone.source.kugou

import com.musicone.source.sdk.SourceMembershipDetector

/** 向宿主提供当前账号的会员状态，查不到或未查询时返回未知。 */
internal class KugouMembershipDeclaration(
    private val state: () -> KugouPackageState?,
) : SourceMembershipDetector {
    override val currentMembership: Boolean?
        get() = state()?.let { it.membership.cached(it.cookie()) }

    override suspend fun detectMembership(): Boolean? {
        val current = state() ?: return null
        return current.request { captured -> current.membership.detectMembership(captured.cookie) }
    }
}
