package com.musicone

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceKind
import com.musicone.sourceintegration.SourcePackageController

/** 收藏、导入与账号共用已有设置层级动效，音源页面只通过公开 SDK 运行。 */
@Composable
internal fun SupplementalSourceScreen(sourceId: String, bottomInset: Dp = 0.dp,
    onBack: () -> Unit, onLocalBackChange: ((() -> Unit)?) -> Unit,
    retainedModel: SupplementalSourceModel? = null,
    initialTarget: com.musicone.source.sdk.SourceSupplementalTarget = com.musicone.source.sdk.SourceSupplementalTarget.Home,
    publicOnly: Boolean = false, initialPage: com.musicone.source.sdk.SourceSupplementalPage? = null,
    showHeader: Boolean = true, onTitleChange: ((String?) -> Unit)? = null) {
    val context = LocalContext.current
    val controller = remember(context) { SourcePackageController.get(context) }
    val installed by controller.installed.collectAsState()
    val instance = remember(sourceId, installed) { controller.running(sourceId) }
    if (instance == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            if (showHeader) SettingsHeader("补充音源", onBack)
            Text("此音源尚未激活，请安装后重启应用。")
        }
        DisposableEffect(sourceId) { onDispose { onLocalBackChange(null) } }
        return
    }
    require(instance.entry.descriptor.kind == SourceKind.SUPPLEMENTAL)
    val model = retainedModel ?: remember(instance) { SupplementalSourceModel(context.applicationContext, instance) }
    val state by model.state.collectAsState()
    val latestBack by rememberUpdatedState(onBack)
    var folderBack by remember(model) { mutableStateOf<(() -> Boolean)?>(null) }
    val back: () -> Unit = remember(model) { {
        if ((model.state.value.page != SupplementalPage.FOLDER || folderBack?.invoke() != true) && !model.back()) latestBack()
    } }
    SideEffect {
        onLocalBackChange(back)
        onTitleChange?.invoke(model.embeddedPageHeader.title ?: supplementalPageTitle(state, instance.entry.descriptor.name))
    }
    LaunchedEffect(model, initialTarget, initialPage) {
        model.enter(forcePublic = publicOnly)
        if (initialPage != null) model.navigate(initialPage, initialTarget, forcePublic = publicOnly, asRoot = true)
        else if (initialTarget != com.musicone.source.sdk.SourceSupplementalTarget.Home) model.startAt(initialTarget)
    }
    ObserveSupplementalAccount(model, state.page)
    DisposableEffect(model) { onDispose {
        onLocalBackChange(null); onTitleChange?.invoke(null)
        if (retainedModel == null) model.close()
    } }
    val scrolls = SupplementalPage.entries.associateWith { rememberLazyListState() }
    val homeScroll = rememberLazyGridState(); val foldersScroll = rememberLazyGridState()
    CompositionLocalProvider(LocalArtworkEntrancePolicy provides LocalArtworkEntrancePolicy.current.copy(fadeArtwork = true)) {
        AnimatedContent(state, Modifier.fillMaxSize(), contentKey = { it.page }, contentAlignment = Alignment.TopStart,
            transitionSpec = { settingsPageTransform(targetState.page.ordinal > initialState.page.ordinal) },
            label = "补充音源层级衔接") { displayed ->
            val page = displayed.page
            Column(Modifier.fillMaxSize()) {
                if (showHeader) Box(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    SettingsHeader(model.embeddedPageHeader.title ?: supplementalPageTitle(displayed, instance.entry.descriptor.name), back, singleLine = true)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                val publicBody: @Composable () -> Unit = {
                when (page) {
                    SupplementalPage.HOME, SupplementalPage.FOLDERS -> SupplementalLibraryHome(displayed, model,
                        if (page == SupplementalPage.HOME) homeScroll else foldersScroll, bottomInset, page == SupplementalPage.FOLDERS)
                    SupplementalPage.FOLDER -> key(displayed.account?.id, displayed.selectedCollection?.identity) {
                        SupplementalFolderScreen(displayed, model, bottomInset) { folderBack = it }
                    }
                    SupplementalPage.SETTINGS -> Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                        com.musicone.sourceintegration.SourceExternalSurface(instance, com.musicone.source.sdk.SourceRoute("settings"),
                            Modifier.fillMaxSize(), mode = "settings", onExit = back)
                    }
                    SupplementalPage.ACCOUNT -> Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                        if (model.pageProvider != null) SupplementalDefaultAccount(displayed, model, back)
                        else instance.AuthenticationContent()
                    }
                    else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
                        LazyColumn(Modifier.widthIn(max = 1120.dp).fillMaxSize(), state = scrolls.getValue(page),
                            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomInset + 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            displayed.error?.let { item("error") { MainSourceInlineMessage(it) } }
                            displayed.message?.let { item("message") { MainSourceInlineMessage(it) } }
                            item("body") { if (page == SupplementalPage.LINK) SupplementalLinkEntry(displayed, model) else SupplementalImportSection(displayed, model) }
                        }
                    }
                }
                }
                SupplementalPageContent(displayed, model, bottomInset, back, publicOnly, publicBody)
                }
            }
        }
    }
}
