package com.musicone.sourceintegration

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.musicone.*
import com.musicone.source.sdk.SourcePageRequest
import com.musicone.source.sdk.SourceSessionStatus

/** 只有包主动调用公共搜索时，才把该次调用交给已有根搜索；不接管自绘搜索路由。 */
internal data class SourcePublicRootSearchFrame(val sourceId: String, val instance: Any?, val accountId: String?,
    val canStart: Boolean, val sessionStatus: SourceSessionStatus? = null)

internal class SourcePublicRootSearchCall(val request: SourcePageRequest.Search,
    val owner: SourcePublicRootSearchFrame) {
    var activated by mutableStateOf(false)
    var retired = false
    var onClose: () -> Unit = {}
}

internal class SourcePublicRootSearchBridge {
    // 资格只在注册时读取，不把实例身份交给结构相等策略合并。
    var frame: SourcePublicRootSearchFrame? = null
    var call by mutableStateOf<SourcePublicRootSearchCall?>(null)
        private set
    val mounted get() = call != null

    fun belongsTo(call: SourcePublicRootSearchCall, frame: SourcePublicRootSearchFrame?): Boolean =
        frame != null && call.owner.sourceId == frame.sourceId && call.owner.instance === frame.instance &&
            call.owner.accountId == frame.accountId && call.owner.sessionStatus == frame.sessionStatus

    fun canHandle(request: SourcePageRequest.Search, surface: SourcePublicSurfaceContext?): Boolean =
        surface?.active == true && surface.separateRoot && frame?.canStart == true &&
            frame?.instance != null && frame?.sourceId == request.sourceId && call == null

    fun register(candidate: SourcePublicRootSearchCall): Boolean {
        if (candidate.retired || call != null || frame?.canStart != true || !belongsTo(candidate, frame)) return false
        call = candidate
        return true
    }

    fun retire(expected: SourcePublicRootSearchCall, notify: Boolean) {
        if (call !== expected) return
        expected.retired = true
        call = null
        // 先交还所有权，回调立即发起的新调用不能被旧收尾覆盖。
        if (notify) expected.onClose()
    }
}

internal val LocalSourcePublicRootSearchBridge = staticCompositionLocalOf<SourcePublicRootSearchBridge?> { null }

/** 首次决定归属后不因根详情或设置覆盖而切回另一套搜索。 */
@Composable
internal fun SourcePublicRootSearchContent(request: SourcePageRequest.Search, onClose: () -> Unit): Boolean {
    val bridge = LocalSourcePublicRootSearchBridge.current ?: return false
    val surface = LocalSourcePublicSurface.current
    val candidate = remember(bridge, request) {
        if (bridge.canHandle(request, surface)) SourcePublicRootSearchCall(request, checkNotNull(bridge.frame)) else null
    } ?: return false
    val close by rememberUpdatedState(onClose)
    var accepted by remember(candidate) { mutableStateOf<Boolean?>(null) }
    if (accepted == false) return false
    SideEffect { candidate.onClose = { close() } }
    DisposableEffect(bridge, candidate) {
        accepted = bridge.register(candidate)
        onDispose { bridge.retire(candidate, notify = false) }
    }
    return true
}

/** 根搜索的配置、首次激活和退场收尾只有此处负责，原组件和运动值保持不变。 */
@Composable
internal fun SourcePublicRootSearchHost(bridge: SourcePublicRootSearchBridge, model: MainSourceSearchViewModel,
    state: MainSourceSearchState, motion: MainSourceSearchMotion, source: MusicSource, page: MusicOnePage,
    revision: Long, accountId: String?, custom: Boolean, ready: Boolean, canStart: Boolean) {
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val instance = remember(source, installed) { controller.running(source.id) }
    val accountRevision by SourceApplicationHost.get(LocalContext.current).accountRevision.collectAsState()
    val account = remember(instance, accountRevision, revision) { instance?.entry?.authentication?.currentAccount() }
    val frame = SourcePublicRootSearchFrame(source.id, instance, account?.account?.id ?: accountId,
        custom && ready && page == MusicOnePage.HOME && canStart && instance?.entry?.search != null, account?.status)
    SideEffect { bridge.frame = frame }
    val call = bridge.call
    LaunchedEffect(source, instance, revision, frame.accountId, account?.status, account?.revision, custom, ready, call) {
        // 独立重组器可能已换了调用；旧effect连清空共享VM的资格也不能保留。
        if (bridge.call !== call) return@LaunchedEffect
        if (call != null && !bridge.belongsTo(call, frame)) {
            model.deactivate()
            // 换号仍可请求当前包退出；替换运行实例后不能再回调旧包。
            bridge.retire(call, notify = call.owner.instance === instance && call.owner.sourceId == source.id)
            return@LaunchedEffect
        }
        if (!ready) return@LaunchedEffect
        if ((!custom && source != MusicSource.LOCAL) || call != null) {
            if (call != null && !call.activated) model.deactivate()
            model.configure(if (call == null) revision else account?.revision ?: revision, source)
            if (call != null && bridge.call === call && !call.activated) {
                model.open()
                model.setQuery(call.request.query)
                if (call.request.submit) model.submit()
                call.activated = true
            }
        } else model.deactivate()
    }
    LaunchedEffect(call, call?.activated, state.opened, motion.mounted) {
        if (call?.activated != true || state.opened || motion.mounted) return@LaunchedEffect
        // StateFlow与独立重组器可能错一帧，不能把首次尚未展开误判为已经退出。
        withFrameNanos { }
        if (bridge.call === call && !model.state.value.opened && !motion.mounted) bridge.retire(call, notify = true)
    }
}
