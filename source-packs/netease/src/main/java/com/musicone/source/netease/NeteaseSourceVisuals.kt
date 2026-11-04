package com.musicone.source.netease

import android.net.Uri
import com.musicone.source.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 包内背景资料只通过公开 URI 状态分享，宿主无需了解文件名或设置键。 */
internal class NeteaseSourceVisuals(private val owner: () -> NeteasePackageState) : SourceVisuals {
    override val supportsCustomBackground = true
    private val states = linkedMapOf<SourceRoute, MutableStateFlow<SourceVisualState>>()
    private var revision = 0L
    private fun routeKey(route: SourceRoute) = SourceRoute(if (route.page == "playlist") "playlist" else "home",
        route.arguments.filterKeys { it == "id" || it == "owned" })
    @Synchronized override fun observe(route: SourceRoute): StateFlow<SourceVisualState> {
        val key = routeKey(route)
        return states.getOrPut(key) { MutableStateFlow(value(key)) }
    }
    private fun value(route: SourceRoute): SourceVisualState {
        val context = owner().environment.context
        val global = NeteaseProfileBackgroundStore.currentUri(context)
        val custom = if (route.page == "playlist") route.arguments["id"]?.let { NeteaseProfileBackgroundStore.playlistUri(context, it) } else global
        return SourceVisualState(custom ?: global.takeIf { route.arguments["owned"] == "true" }, revision, customBackground = custom != null)
    }
    @Synchronized fun refresh() { revision++; states.forEach { (route, state) -> state.value = value(route) } }
    override suspend fun setCustomBackground(route: SourceRoute, croppedImageUri: String?) {
        val context = owner().environment.context
        val target = if (route.page == "playlist") NeteaseBackgroundTarget.Playlist(requireNotNull(route.arguments["id"])) else NeteaseBackgroundTarget.Global
        if (croppedImageUri == null) check(NeteaseProfileBackgroundStore.clear(context, target)) { "背景恢复失败" }
        else {
            val bitmap = NeteaseProfileBackgroundStore.loadPreview(context, Uri.parse(croppedImageUri)) ?: error("无法读取背景图片")
            try {
                check(NeteaseProfileBackgroundStore.saveCrop(context, target, bitmap,
                    NeteaseCropSelection(aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)))) { "背景保存失败" }
            } finally { bitmap.recycle() }
        }
        refresh()
    }
}
