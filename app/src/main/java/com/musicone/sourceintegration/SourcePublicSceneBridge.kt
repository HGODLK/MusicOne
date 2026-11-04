package com.musicone.sourceintegration

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourcePageRequest
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.musicone.*

/** 只桥接公共整页的原导航状态；离屏预挂载页不能接管根控件。 */
internal class SourcePublicSceneBridge(
    val settings: SettingsNavigation,
    val settingsMotion: PageMotion,
    val playerMotion: PageMotion,
    val measurements: BottomBarMeasurements,
) {
    private data class Ownership(val priority: Int, val activation: Long)
    private val scenes = mutableStateMapOf<SourcePublicScene, Ownership>()
    private var activation = 0L
    // 和 QQ 一样由宿主已落定页面选择根控件，不等待两张包页面异步交接。
    var selectedRoot: SourceRootIdentity? = null
    val active: SourcePublicScene? get() = scenes.entries.filter {
        it.key.rootIdentity?.let { root -> root == selectedRoot } ?: (it.value.priority > 0)
    }.maxWithOrNull(compareBy<Map.Entry<SourcePublicScene, Ownership>> {
            if (it.key.rootIdentity != null) 1 else it.value.priority
        }
            .thenBy { it.value.activation })?.key
    fun update(scene: SourcePublicScene, priority: Int) {
        val previous = scenes[scene]
        // 独立重组器换页可能前后错帧，同层级由新激活页接管，旧页重组不能夺回。
        val order = if (priority > 0 && (previous == null || previous.priority == 0)) ++activation
            else previous?.activation ?: 0L
        scenes[scene] = Ownership(priority, order)
    }
    fun remove(scene: SourcePublicScene) {
        scenes.remove(scene)
        scene.header = null
        scene.rootHeader = null
        scene.back = null
    }
    fun openSettings(owner: SourcePublicScene, origin: Rect, rootOffset: Offset) {
        if (active !== owner) return
        settings.origin = origin.translate(rootOffset)
        settings.openSettings()
    }
}

internal class SourcePublicScene(
    val playlists: RecommendationNavigation,
    val entities: EntityNavigation,
    val search: MainSourceSearchMotion,
    val searchHomeGuard: MainSourceSearchHomeGuard? = null,
    val rootIdentity: SourceRootIdentity? = null,
) {
    var header: (@Composable () -> Unit)? = null
    var rootHeader: SourcePublicRootHeader? by mutableStateOf(null)
    var back: (@Composable (GraphicsLayer?, Rect) -> Unit)? = null
    val detailMounted get() = playlists.motion.mounted || entities.pages.isNotEmpty() || search.mounted
    val overlayBackdrop get() = rootIdentity == null || rootHeader == null || detailMounted
}

/** 独立重组器内的活动页身份及跨根坐标，均由宿主提供。 */
internal data class SourcePublicSurfaceContext(val active: Boolean, val interactive: Boolean, val rootOffset: Offset,
    val priority: Int = 1, val separateRoot: Boolean = true,
    val consumedInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    val rootIdentity: SourceRootIdentity? = null, val coordinates: SourceRootCoordinates? = null) {
    val currentRootOffset: Offset get() = coordinates?.offset ?: rootOffset
}
internal val LocalSourcePublicSurface = staticCompositionLocalOf<SourcePublicSurfaceContext?> { null }
internal val LocalSourcePublicSceneBridge = staticCompositionLocalOf<SourcePublicSceneBridge?> { null }

@Composable
internal fun BindSourcePublicScene(scene: SourcePublicScene) {
    val bridge = LocalSourcePublicSceneBridge.current ?: return
    val surface = LocalSourcePublicSurface.current
    SideEffect { bridge.update(scene, if (surface == null) 2 else if (surface.active) surface.priority else 0) }
    DisposableEffect(bridge, scene) { onDispose { bridge.remove(scene) } }
}

@Composable
internal fun SourcePublicSceneHeader(scene: SourcePublicScene?) { scene?.header?.invoke() }

/** 页头保留原组件与动效，唯一出口放到根设置表面之上。 */
@Composable
internal fun BindSourcePublicSceneHeader(scene: SourcePublicScene, request: SourcePageRequest, source: MusicSource,
    backdrop: GraphicsLayer, bounds: Rect, onSearch: () -> Unit, onSettings: () -> Unit,
    onSettingsBounds: (Rect) -> Unit, rootPage: Boolean = false) {
    val chrome = checkNotNull(LocalSourcePublicSceneBridge.current)
    val surface = LocalSourcePublicSurface.current
    val context by rememberUpdatedState(currentCompositionLocalContext)
    val currentBounds by rememberUpdatedState(bounds)
    val currentSurface by rememberUpdatedState(surface)
    val rootOffset = { currentSurface?.currentRootOffset ?: Offset.Zero }
    val search by rememberUpdatedState(onSearch)
    val settings by rememberUpdatedState(onSettings)
    val settingsBounds by rememberUpdatedState(onSettingsBounds)
    scene.rootHeader = if (rootPage) remember(scene) {
        SourcePublicRootHeader({ search() }, { settings() }, { settingsBounds(it.translate(-rootOffset())) })
    } else null
    val header: @Composable () -> Unit = remember(scene, request, source, rootPage) {
        {
            if (!rootPage && (request is SourcePageRequest.Home || request is SourcePageRequest.My) && scene.entities.pages.isEmpty()) {
                CompositionLocalProvider(context) {
                    Box(Modifier.fillMaxSize().offset {
                        val headerOffset = if (currentSurface?.separateRoot == true) rootOffset() else currentBounds.topLeft
                        IntOffset(headerOffset.x.roundToInt(), headerOffset.y.roundToInt())
                    }) {
                        val page = if (request is SourcePageRequest.My) MusicOnePage.MY else MusicOnePage.HOME
                        PrimaryPageHeader(page, { page.ordinal.toFloat() }, backdrop, currentBounds.translate(rootOffset()),
                            { search() }, { settings() },
                            { primaryHeaderMotionVisibility(scene.playlists.motion.value, chrome.playerMotion.value) },
                            Modifier.align(Alignment.TopCenter).padding(start = if (rootPage) 0.dp else 48.dp),
                            searchProgress = { scene.search.menu.value }, settingsProgress = { chrome.settingsMotion.value },
                            settingsMounted = { chrome.settingsMotion.mounted },
                            onSettingsBounds = { settingsBounds(it.translate(-rootOffset())) }, localMode = source == MusicSource.LOCAL)
                    }
                }
            }
        }
    }
    scene.header = header
}

@Composable
internal fun SourcePublicSceneBack(scene: SourcePublicScene?, settingsBackdrop: GraphicsLayer?, settingsBounds: Rect) {
    scene?.back?.invoke(settingsBackdrop, settingsBounds)
}

/** 统一返回与公共详情使用同一导航对象，避免包级返回抢走收起动画。 */
@Composable
internal fun BindSourcePublicSceneBack(scene: SourcePublicScene, toolbar: PlaylistToolbarOverlayState,
    searchState: MainSourceSearchState, searchModel: MainSourceSearchViewModel, backdrop: GraphicsLayer, bounds: Rect) {
    val chrome = checkNotNull(LocalSourcePublicSceneBridge.current)
    val surface = LocalSourcePublicSurface.current
    val context by rememberUpdatedState(currentCompositionLocalContext)
    val currentBounds by rememberUpdatedState(bounds)
    val currentSurface by rememberUpdatedState(surface)
    val currentSearch by rememberUpdatedState(searchState)
    val back: @Composable (GraphicsLayer?, Rect) -> Unit = remember(scene, toolbar, searchModel) {
        { settingsBackdrop, settingsBounds ->
            CompositionLocalProvider(context) {
                UnifiedBackButton(scene.entities, chrome.settings, scene.playlists, toolbar,
                    currentSearch, searchModel, scene.search, chrome.playerMotion,
                    settingsBackdrop ?: backdrop, if (settingsBackdrop != null) settingsBounds
                    else currentBounds.translate(currentSurface?.currentRootOffset ?: Offset.Zero))
            }
        }
    }
    scene.back = back
}
