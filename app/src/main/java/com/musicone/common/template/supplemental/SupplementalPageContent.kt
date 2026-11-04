package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourceExternalSurface

/** 接管页只替换页面主体，返回、安全区和层级动效继续归宿主管理。 */
@Composable
internal fun SupplementalPageContent(state: SupplementalState, model: SupplementalSourceModel, inset: Dp,
    onBack: () -> Unit, publicOnly: Boolean, publicBody: @Composable () -> Unit) {
    val provider = model.pageProvider
    if (provider == null) { publicBody(); return }
    val page = state.page.publicPage()
    val policy = provider.supplementalPagePolicy(page)
    if (policy.mode == SourceSupplementalPageMode.DISABLED ||
        ((publicOnly || state.publicPage || policy.mode == SourceSupplementalPageMode.DEFAULT) && !supplementalPublicPageAvailable(model.entry, page))) {
        MainSourceInlineMessage("此音源未开放该页面")
        return
    }
    if (publicOnly || state.publicPage || policy.mode == SourceSupplementalPageMode.DEFAULT) { publicBody(); return }
    val target = model.currentTarget(state)
    val revision = model.callbackRevision()
    val navigationRevision = state.navigationRevision
    val callbackCurrent = { model.callbackCurrent(revision) && model.state.value.navigationRevision == navigationRevision }
    var failure by remember(model, page, target, revision, navigationRevision) { mutableStateOf<String?>(null) }
    val latestBack by rememberUpdatedState(onBack)
    val actions = remember(model, revision, navigationRevision) { object : SourceSupplementalPageActions {
        private fun current(block: () -> Unit) { if (callbackCurrent()) block() }
        override fun navigate(page: SourceSupplementalPage, target: SourceSupplementalTarget) = current { model.navigate(page, target) }
        override fun resolve(input: String) = current { model.input(input); model.resolveInput() }
        override fun openContent(content: SourceContent) = current { model.openContent(content) }
        override fun play(contents: List<SourceContent>) = current { model.importContents(contents, true) }
        override fun importContents(contents: List<SourceContent>, destinationId: String, destinationTitle: String) = current {
            model.target(destinationId, destinationTitle); model.importContents(contents)
        }
        override fun subscribe(collection: SourceRemoteCollection) = current { model.subscribe(collection) }
        override fun refreshSubscription(collection: SourceRemoteCollection) = current { model.refreshSubscription(collection) }
        override fun removeSubscription(collection: SourceRemoteCollection) = current { model.removeCollectionSubscription(collection) }
    } }
    val contextPage = page
    val contextTarget = target
    val sharedActions = actions
    val context = remember(model, page, target, revision, navigationRevision, inset, actions) { object : SourceSupplementalPageContext {
        override val page = contextPage
        override val target = contextTarget
        override val bottomInsetDp = inset.value
        override val actions = sharedActions
        override fun onBack() { if (callbackCurrent()) latestBack() }
        override fun onFailure(message: String) { if (callbackCurrent()) failure = message.ifBlank { "音源页面显示失败" } }
        override fun openPublic(page: SourceSupplementalPage, target: SourceSupplementalTarget) {
            if (callbackCurrent() && supplementalPublicPageAvailable(model.entry, page)) model.navigate(page, target, forcePublic = true)
        }
        @Composable override fun PublicContent(page: SourceSupplementalPage, target: SourceSupplementalTarget) {
            if (model.pageAvailable(page) && supplementalPublicPageAvailable(model.entry, page)) {
                var embeddedBack by remember(model, page, target) { mutableStateOf<(() -> Unit)?>(null) }
                val headerOwner = remember(model, page, target) { Any() }
                DisposableEffect(model, headerOwner) { onDispose { model.embeddedPageHeader.clear(headerOwner) } }
                BackHandler(enabled = embeddedBack != null && callbackCurrent()) {
                    if (callbackCurrent()) embeddedBack?.invoke()
                }
                SupplementalSourceScreen(model.sourceId, inset, onBack = {
                    if (callbackCurrent()) { model.embeddedPageHeader.clear(headerOwner); onBack() }
                }, onLocalBackChange = { handler ->
                    if (handler == null) {
                        embeddedBack = null; model.embeddedPageHeader.clear(headerOwner)
                    } else if (callbackCurrent()) {
                        embeddedBack = handler
                        model.embeddedPageHeader.updateBack(headerOwner) {
                            if (callbackCurrent()) { handler(); true } else false
                        }
                    }
                }, initialTarget = target, publicOnly = true, initialPage = page,
                    showHeader = false, onTitleChange = { title ->
                        if (title == null) model.embeddedPageHeader.clear(headerOwner)
                        else if (callbackCurrent()) model.embeddedPageHeader.update(headerOwner, title)
                    })
            } else MainSourceInlineMessage("此音源未提供对应公共页面能力")
        }
    } }
    if (failure != null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MainSourceInlineMessage(checkNotNull(failure))
            state.error?.let { MainSourceInlineMessage(it) }
            TextButton(onClick = { failure = null }) { Text("重试页面") }
            if (policy.allowPublicFallback && supplementalPublicPageAvailable(model.entry, page)) TextButton(onClick = {
                model.navigate(page, target, forcePublic = true)
            }) { Text("使用公共页面") }
        }
    } else Column(Modifier.fillMaxSize()) {
        state.error?.let { MainSourceInlineMessage(it) }
        state.message?.let { MainSourceInlineMessage(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(page, target, navigationRevision) {
                SourceExternalSurface(model.instance, SourceRoute("supplemental-page"), Modifier.fillMaxSize(),
                    mode = "supplemental-page", supplementalPage = context,
                    onFailure = { if (callbackCurrent()) failure = it }, onExit = onBack)
            }
        }
    }
}
