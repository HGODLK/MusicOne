package com.musicone.sourceintegration

import com.musicone.prepareCachedArtwork
import com.musicone.PlatformPreferences
import com.musicone.source.sdk.SourceArtwork
import com.musicone.source.sdk.SourceCachedArtworkComponents
import kotlinx.coroutines.CancellationException

/** 给音源包用的封面缓存预热接口，只操作宿主自己的图片缓存，不直接把 Bitmap 或文件交给包。 */
internal class SourcePublicCachedArtworkComponents(private val access: SourcePublicComponentAccess) : SourceCachedArtworkComponents {
    override suspend fun prepareCachedArtwork(artworks: List<SourceArtwork>, maxSidePx: Int) {
        checkActive()
        require(maxSidePx in 1..768) { "封面准备尺寸须在1到768像素之间" }
        // 启动尚未发布实例时只准备当前主音源，避免其他包挤占首屏封面缓存。
        if (SourcePackageController.get(access.context).running(access.sourceId) == null &&
            PlatformPreferences(access.context).selectedSource().id != access.sourceId) return
        val account = access.accountKey()
        prepareCachedArtwork(artworks.map { it.url }, access.context.applicationContext, maxSidePx)
        checkActive()
        if (account != access.accountKey()) throw CancellationException("封面准备期间账号已变化")
    }

    private fun checkActive() {
        try { access.check() }
        catch (invalid: IllegalStateException) {
            throw CancellationException("封面准备租约已失效").also { it.initCause(invalid) }
        }
    }
}
