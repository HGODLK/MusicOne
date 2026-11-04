package com.musicone.sourceintegration

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.musicone.MusicOnePage
import com.musicone.MusicSource
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.SourcePageState
import com.musicone.source.sdk.SourceRoute

/** 宿主继续负责两张根页的滑动，包负责其内部页面和返回栈。 */
@Stable
internal class SourceRootPages(private val instance: SourceInstance?, private val pageState: State<SourcePageState>,
    private val startup: SourceStartupState) {
    val initializing: Boolean get() = startup.initializing
    val preparing: Boolean get() = startup.initializing || (instance == null && startup.error != null)
    @Composable fun Preparation(source: MusicSource, page: MusicOnePage, bottomInset: Dp, onChooseSource: () -> Unit) {
        SourcePreparationContent(source, page, bottomInset, startup.error, onChooseSource)
    }
    val isCustom: Boolean get() = instance?.entry?.descriptor?.useCustomPages == true
    val atRoot: Boolean get() = pageState.value.atRoot
    val hostRootTransition: Boolean get() = isCustom && instance?.entry?.descriptor?.id == MusicSource.KUGOU.id
    fun rootIdentity(page: MusicOnePage): SourceRootIdentity? =
        if (hostRootTransition) SourceRootIdentity(MusicSource.KUGOU.id, page) else null
    val supportsSearch: Boolean get() = instance?.entry?.search != null
    private var searchPage by mutableStateOf<MusicOnePage?>(null)
    private var requestRevision by mutableLongStateOf(0)
    fun openSearch(page: MusicOnePage) { searchPage = page; requestRevision++ }
    fun rootChanged() { searchPage = null }
    @Composable fun Content(page: MusicOnePage, active: Boolean, bottomInset: Dp, modifier: Modifier = Modifier,
        onExit: () -> Unit = {}, interactive: Boolean = true) {
        instance ?: return
        val route = if (active && searchPage == page) "search" else page.name.lowercase()
        SourceExternalSurface(instance, SourceRoute(route, mapOf("embedded" to "true", "active" to active.toString(),
            "bottomInsetDp" to bottomInset.value.toString(), "requestRevision" to requestRevision.toString())), modifier,
            onExit = onExit, interactive = active && interactive, rootIdentity = rootIdentity(page))
    }
    @Composable fun Back(enabled: Boolean) {
        BackHandler(enabled = enabled && isCustom && (!atRoot || searchPage != null)) {
            // 包内返回优先；搜索根页再由宿主收起，避免直接退出应用。
            if (!runCatching { instance?.onBack() == true }.getOrDefault(false)) searchPage = null
        }
    }
}

@Composable
internal fun rememberSourceRootPages(source: MusicSource, page: MusicOnePage): SourceRootPages {
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val startup by controller.startup.collectAsState()
    val instance = remember(source, installed) { controller.running(source.id) }
    val state = instance?.entry?.pageState?.collectAsState() ?: remember(instance) { mutableStateOf(SourcePageState()) }
    val result = remember(instance, startup) { SourceRootPages(instance, state, startup) }
    LaunchedEffect(result, page) { result.rootChanged() }
    return result
}
