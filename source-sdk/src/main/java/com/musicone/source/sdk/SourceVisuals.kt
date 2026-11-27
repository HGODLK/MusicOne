package com.musicone.source.sdk

import kotlinx.coroutines.flow.StateFlow

/** 背景由音源提供，宿主只读取可访问的 URI 和公开样式参数，不碰包私有路径。 */
data class SourceVisualState(val backgroundUri: String? = null, val revision: Long = 0,
    val foregroundArgb: Long? = null, val customBackground: Boolean = false)

interface SourceVisuals {
    val supportsCustomBackground: Boolean get() = false
    // 全局使用 home，歌单使用 playlist 与稳定 id 参数；状态必须在背景更新后发布。
    fun observe(route: SourceRoute = SourceRoute()): StateFlow<SourceVisualState>
    // 传入宿主裁剪好的图片 URI，传 null 恢复默认；返回成功后 observe 需同步更新状态。
    suspend fun setCustomBackground(route: SourceRoute, croppedImageUri: String?) {
        throw UnsupportedOperationException("此音源未开放背景设置")
    }
}
