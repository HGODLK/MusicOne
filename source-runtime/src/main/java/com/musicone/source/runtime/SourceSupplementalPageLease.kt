package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.musicone.source.sdk.*
import java.util.concurrent.atomic.AtomicBoolean

/** 页面上下文与业务操作绑定当前实例、账号和挂载周期。 */
internal fun guardSupplementalPages(provider: SourceSupplementalPageProvider, sourceId: String,
    operations: SourceOperationLease): SourceSupplementalPageProvider = object : SourceSupplementalPageProvider {
    override fun supplementalPagePolicy(page: SourceSupplementalPage) = operations.read { provider.supplementalPagePolicy(page) }
    override fun publicSubscriptionIdentity() = operations.read { provider.publicSubscriptionIdentity() }
    @Composable override fun SupplementalPage(context: SourceSupplementalPageContext) {
        operations.read { Unit }
        val account = operations.accountVersion()
        val mounted = remember(context, account) { AtomicBoolean(true) }
        DisposableEffect(mounted) { onDispose { mounted.set(false) } }
        val guarded = remember(context, account) {
            val checkAccount = operations.deferredCheck()
            fun checkCurrent() { check(mounted.get()) { "音源页面已关闭" }; checkAccount() }
            fun checkTarget(value: SourceSupplementalTarget) {
                val id = when (value) {
                    is SourceSupplementalTarget.Collection -> value.collection.identity.sourceId
                    is SourceSupplementalTarget.Content -> value.content.identity.sourceId
                    else -> sourceId
                }
                require(id == sourceId) { "不能打开其他音源的补充页面" }
            }
            fun checkContents(values: List<SourceContent>) {
                require(values.all { it.identity.sourceId == sourceId && (it.playableTrack?.track?.identity?.sourceId ?: sourceId) == sourceId }) { "不能提交其他音源的内容" }
            }
            fun dispatch(action: () -> Unit) { checkCurrent(); operations.dispatch(sourceId, action) }
            object : SourceSupplementalPageContext {
                override val page get() = context.page
                override val target get() = context.target
                override val bottomInsetDp get() = context.bottomInsetDp
                override fun onBack() = dispatch { context.onBack() }
                override fun onFailure(message: String) = dispatch { context.onFailure(message) }
                override fun openPublic(page: SourceSupplementalPage, target: SourceSupplementalTarget) = dispatch { checkTarget(target); context.openPublic(page, target) }
                @Composable override fun PublicContent(page: SourceSupplementalPage, target: SourceSupplementalTarget) {
                    checkCurrent(); checkTarget(target); context.PublicContent(page, target)
                }
                override val actions = object : SourceSupplementalPageActions {
                    override fun navigate(page: SourceSupplementalPage, target: SourceSupplementalTarget) = dispatch { checkTarget(target); context.actions.navigate(page, target) }
                    override fun resolve(input: String) = dispatch { context.actions.resolve(input) }
                    override fun openContent(content: SourceContent) = dispatch { checkContents(listOf(content)); context.actions.openContent(content) }
                    override fun play(contents: List<SourceContent>) = dispatch { checkContents(contents); context.actions.play(contents) }
                    override fun importContents(contents: List<SourceContent>, destinationId: String, destinationTitle: String) = dispatch { checkContents(contents); context.actions.importContents(contents, destinationId, destinationTitle) }
                    override fun subscribe(collection: SourceRemoteCollection) = dispatch { checkTarget(SourceSupplementalTarget.Collection(collection)); context.actions.subscribe(collection) }
                    override fun refreshSubscription(collection: SourceRemoteCollection) = dispatch { checkTarget(SourceSupplementalTarget.Collection(collection)); context.actions.refreshSubscription(collection) }
                    override fun removeSubscription(collection: SourceRemoteCollection) = dispatch { checkTarget(SourceSupplementalTarget.Collection(collection)); context.actions.removeSubscription(collection) }
                }
            }
        }
        provider.SupplementalPage(guarded)
    }
}
