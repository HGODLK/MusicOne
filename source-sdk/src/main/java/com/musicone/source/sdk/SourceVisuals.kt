package com.musicone.source.sdk

import kotlinx.coroutines.flow.StateFlow

/** 背景由音源声明，宿主只消费可读 URI 与公开视觉参数，不探查包私有路径。 */
data class SourceVisualState(val backgroundUri: String? = null, val revision: Long = 0,
    val foregroundArgb: Long? = null, val customBackground: Boolean = false)

interface SourceVisuals {
    val supportsCustomBackground: Boolean get() = false
    // 全局使用 home，歌单使用 playlist 与稳定 id 参数；状态必须在背景更新后发布。
    fun observe(route: SourceRoute = SourceRoute()): StateFlow<SourceVisualState>
    // 输入为宿主已裁剪图片的可读 URI；null 恢复默认，成功返回后 observe 必须反映新状态。
    suspend fun setCustomBackground(route: SourceRoute, croppedImageUri: String?) {
        throw UnsupportedOperationException("此音源未开放背景设置")
    }
}
