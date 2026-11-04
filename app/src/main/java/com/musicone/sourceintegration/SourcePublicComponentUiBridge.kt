package com.musicone.sourceintegration

import android.content.Context
import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*

/** 绑定音源后复用公共控件；不会把宿主模型交给包。 */
internal class SourcePublicComponentUiBridge(private val context: Context) : SourceComponentUi {
    override fun forSource(sourceId: String, checkAccess: () -> Unit): SourceComponentScope {
        checkAccess()
        require(sourceId.isNotBlank()) { "音源身份不能为空" }
        val access = SourcePublicComponentAccess(context, sourceId, checkAccess)
        val home = SourcePublicHomeComponents(access)
        return object : SourceComponentScope,
            SourceLoadedHomeComponents by home,
            SourceHomeShelfComponents by SourcePublicHomeShelfComponents(access),
            SourceCachedArtworkComponents by SourcePublicCachedArtworkComponents(access),
            SourceTrackComponents by SourcePublicTrackComponents(access),
            SourceHomeComponents by home,
            SourceLibraryComponents by SourcePublicLibraryComponents(access),
            SourceBasicComponents by SourcePublicBasicComponents(access),
            SourceSupplementalComponents by SourcePublicSupplementalComponents(access) { }
    }
}

internal class SourcePublicComponentAccess(val context: Context, val sourceId: String, private val guard: () -> Unit) {
    fun accountId(): String? = SourcePackageController.get(context).running(sourceId)?.entry?.authentication?.currentAccount()?.account?.id
    fun accountKey(): Triple<SourceSessionStatus?, String?, Long?> {
        val state = SourcePackageController.get(context).running(sourceId)?.entry?.authentication?.currentAccount()
        return Triple(state?.status, state?.account?.id, state?.revision)
    }
    fun check() = guard()
    fun check(track: SourceTrackDetails) { check(); require(track.track.identity.sourceId == sourceId) { "不能显示其他音源的歌曲" } }
    fun check(collection: SourceCollection) { check(); require(collection.identity.sourceId == sourceId) { "不能显示其他音源的歌单" } }
    fun check(identity: TrackIdentity) { check(); require(identity.sourceId == sourceId) { "内容不属于当前音源" } }
    fun check(tracks: List<SourceTrackDetails>) { check(); tracks.forEach(::check) }
    private val events = SourcePublicEventGuard(::check, ::accountKey)
    fun event(action: () -> Unit): () -> Unit = events.event(action)
    fun <T> valueEvent(action: (T) -> Unit): (T) -> Unit = events.valueEvent(action)
    @Composable fun Host(content: @Composable () -> Unit) { check(); SourceHostUiResources(context, content) }
}
