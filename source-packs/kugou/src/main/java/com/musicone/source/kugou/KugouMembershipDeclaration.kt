package com.musicone.source.kugou

import com.musicone.source.sdk.SourceMembershipDetector

/** 只向宿主声明已核对的音乐会员状态，查询失败和未查询保持未知。 */
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
