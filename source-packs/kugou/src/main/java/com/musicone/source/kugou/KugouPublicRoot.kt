package com.musicone.source.kugou

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.musicone.source.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow

/** 包仅保存自己的根页归属；搜索和“我的”的内部导航、状态均由公共整页维护。 */
internal class KugouPublicRoot(
    private val owner: KugouPackageState,
    private val home: KugouHomeController,
    private val homeCollections: KugouHomeCollections,
    private val actions: KugouHomeActions,
    private val mutablePage: MutableStateFlow<SourcePageState>,
) {
    private var rootPage = "home"
    private val openedSearch = mutableSetOf<String>()
    private var openedPage by mutableStateOf<SourcePageRequest?>(null)
    private var openedPageRoot = "home"
    private var collectionOpen by mutableStateOf(false)
    private var pageBack: (() -> Unit)? = null

    fun onBack(): Boolean {
        if (rootPage != "my" && (openedPage == null || openedPageRoot != rootPage)) return false
        return pageBack?.let { it(); true } ?: false
    }

    @Composable fun Content(route: SourceRoute) {
        val active = route.arguments["active"] != "false"
        val inset = route.arguments["bottomInsetDp"]?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        val ui = owner.environment.host.contentUi
        val pages = ui?.pages
        val componentUi = ui?.components
        val components = remember(componentUi) { componentUi?.forSource(KUGOU_SOURCE_ID) }
        val shelves = components as? SourceHomeShelfComponents
        val rootUi = pages as? SourceRootPageUi
        if (pages == null || components == null || shelves == null || rootUi == null) {
            Text("请更新宿主，以使用公共页面与组件", color = MaterialTheme.colorScheme.onBackground)
            return
        }
        val visible = if (route.page == "search") rootPage else route.page.lowercase()
        val recentOpen = remember {
            KugouRecentOpenRequest(owner.scope,
                { KugouHomeOwner(owner.account()?.userId, owner.revision.get()) },
                { home.state.value }, home::ensureRecent)
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateAsState()
        val canOpenRecent = active && visible != "my" && openedPage == null && !collectionOpen
        SideEffect { recentOpen.setAvailable(canOpenRecent && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        SideEffect {
            if (active) {
                rootPage = if (visible == "my") "my" else "home"
                // 公共“我的”自带标题，不能再叠一份宿主根标题。
                mutablePage.value = SourcePageState(rootPage,
                    atRoot = rootPage != "my" && (openedPage == null || openedPageRoot != rootPage))
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            recentOpen.setAvailable(canOpenRecent)
        }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { recentOpen.setAvailable(false) }
        DisposableEffect(recentOpen) { onDispose { recentOpen.setAvailable(false) } }
        val visibilityOwner = remember { Any() }
        val shown = canOpenRecent && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        LaunchedEffect(shown) {
            home.updateVisibility(visibilityOwner, shown)
            if (shown) home.load()
        }
        DisposableEffect(visibilityOwner) { onDispose { home.updateVisibility(visibilityOwner, false) } }
        val revision = route.arguments["requestRevision"].orEmpty()
        LaunchedEffect(active, route.page, revision) {
            if (active && route.page == "search" && openedSearch.add("$rootPage:$revision")) {
                openedPageRoot = rootPage
                openedPage = SourcePageRequest.Search(KUGOU_SOURCE_ID)
            }
        }
        val destination = openedPage.takeIf { active && openedPageRoot == visible }
        val search = destination as? SourcePageRequest.Search
        val replacement = destination?.takeUnless { it is SourcePageRequest.Search }
        if (replacement != null) {
            PublicPage(pages, replacement, inset, active) { openedPage = null; home.load() }
        } else {
            Box(Modifier.fillMaxSize()) {
                // 搜索与来源页使用独立槽位，展开和返回都不销毁原列表及滚动状态。
                key("root", visible) {
                    if (visible == "my") {
                        val request = remember { SourcePageRequest.My(KUGOU_SOURCE_ID) }
                        PublicPage(pages, request, inset, active && search == null, rootUi) { owner.environment.host.exitSource() }
                    } else {
                        HomeContent(requireNotNull(ui), components, shelves, rootUi, canOpenRecent,
                            visible, inset, recentOpen)
                    }
                }
                if (search != null) key("search", search) {
                    Box(Modifier.fillMaxSize().pointerInput(search) {
                        // 只接住子控件未处理的触摸，避免搜索准备和退出时点穿来源卡片。
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                        }
                    }) {
                        PublicPage(pages, search, inset, active) { openedPage = null; home.load() }
                    }
                }
            }
        }
    }

    @Composable private fun HomeContent(ui: SourceContentUi, components: SourceComponentScope,
        shelves: SourceHomeShelfComponents, rootUi: SourceRootPageUi, reportScroll: Boolean,
        visible: String, inset: Float, recentOpen: KugouRecentOpenRequest) {
        val snapshot by home.state.collectAsState()
        val player = owner.environment.host.playerState?.collectAsState()?.value ?: SourcePlayerState()
        fun open(request: SourcePageRequest) { openedPageRoot = visible; openedPage = request }
        val accountId = owner.account()?.userId
        val sameOwner = snapshot.owner != null && snapshot.owner?.accountId == accountId
        // 换号期间沿用原首页结构，但不显示上一账号的数据。
        val data = if (sameOwner) snapshot else KugouHomeState()
        val daily = remember(accountId, sameOwner, data.dailyDate, data.daily) {
            homeCollections.collection("daily", data.dailyDate ?: kugouHomeDayKey(), data.daily.takeIf { sameOwner }.orEmpty())
        }
        val recent = remember(accountId, sameOwner, data.recent) {
            homeCollections.collection("recent", null, data.recent.tracks.takeIf { sameOwner }.orEmpty())
        }
        val currentRecent by rememberUpdatedState(recent)
        val currentRecentSnapshot by rememberUpdatedState(data.recent)
        // 保留来源卡，交给已有根歌单导航消费同一份共享封面过渡。
        fun openCollection(collection: SourceCollection) {
            recentOpen.setAvailable(false)
            homeCollections.retain(collection)
            collectionOpen = true
            try {
                ui.openCollection(collection) {
                    homeCollections.release(collection)
                    collectionOpen = false
                }
            } catch (failure: Exception) {
                homeCollections.release(collection)
                collectionOpen = false
                throw failure
            }
        }
        fun openDaily() {
            if (accountId == null) open(SourcePageRequest.Authentication(KUGOU_SOURCE_ID))
            else if (!sameOwner || !data.dailyLoaded) home.refreshDaily()
            else openCollection(daily)
        }
        fun openRecent() {
            if (accountId == null) open(SourcePageRequest.Authentication(KUGOU_SOURCE_ID))
            else recentOpen.open { loaded ->
                val collection = if (loaded.recent === currentRecentSnapshot) currentRecent
                    else homeCollections.collection("recent", null, loaded.recent.tracks)
                openCollection(collection)
            }
        }
        KugouHomePage(data, components, shelves, rootUi, reportScroll, player, daily, recent, inset,
            actions::radio, ::openDaily, ::openRecent,
            { home.refreshRecommendations() }, ::openCollection,
            { if (it.stableId == recent.stableId && (!sameOwner || !data.recentLoaded)) {
                    if (accountId == null) open(SourcePageRequest.Authentication(KUGOU_SOURCE_ID))
                    else home.refreshRecent()
                }
                else actions.playCollection(it) },
            actions::playRecommendation)
    }

    /** 在包页面现有 Activity 环境内嵌入公共整页，继续由公共实现维护内部导航。 */
    @Composable private fun PublicPage(pages: SourcePageUi, request: SourcePageRequest,
        bottomInset: Float, active: Boolean, rootUi: SourceRootPageUi? = null, onClose: () -> Unit) {
        val myLifecycle = LocalLifecycleOwner.current.lifecycle
        val close by rememberUpdatedState(onClose)
        val backOwner = remember(myLifecycle, request) { object : OnBackPressedDispatcherOwner {
            override val lifecycle = myLifecycle
            override val onBackPressedDispatcher = OnBackPressedDispatcher { close() }
        } }
        DisposableEffect(backOwner, active) {
            val back = { backOwner.onBackPressedDispatcher.onBackPressed() }
            if (active) pageBack = back
            onDispose { if (pageBack === back) pageBack = null }
        }
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner) {
            if (rootUi != null) rootUi.RootContent(request, Modifier.fillMaxSize(), bottomInset) { close() }
            else pages.Content(request, Modifier.fillMaxSize(), bottomInset) { close() }
        }
    }
}
