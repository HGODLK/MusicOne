package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 公共完整页面的公开请求。页面归属始终由请求决定，与宿主当前选择的音源无关。 */
sealed interface SourcePageRequest {
    val sourceId: String
    data class Home(override val sourceId: String) : SourcePageRequest
    data class My(override val sourceId: String) : SourcePageRequest
    data class Search(override val sourceId: String, val query: String = "", val submit: Boolean = false) : SourcePageRequest
    data class Collection(val collection: SourceCollection) : SourcePageRequest {
        override val sourceId get() = collection.identity.sourceId
    }
    data class Artist(override val sourceId: String, val artist: SourceEntity) : SourcePageRequest
    data class Album(override val sourceId: String, val album: SourceEntity) : SourcePageRequest
    data class DailyRecommendations(override val sourceId: String) : SourcePageRequest
    data class Recent(override val sourceId: String) : SourcePageRequest
    data class Similar(val track: SourceTrackDetails) : SourcePageRequest {
        override val sourceId get() = track.track.identity.sourceId
    }
    data class Supplemental(override val sourceId: String,
        val target: SourceSupplementalTarget = SourceSupplementalTarget.Home) : SourcePageRequest {
        init {
            val targetSource = when (target) {
                is SourceSupplementalTarget.Collection -> target.collection.identity.sourceId
                is SourceSupplementalTarget.Content -> target.content.identity.sourceId
                else -> sourceId
            }
            require(targetSource == sourceId) { "补充页面目标必须属于请求音源" }
        }
    }
    data class Authentication(override val sourceId: String) : SourcePageRequest
    data class Settings(override val sourceId: String) : SourcePageRequest
}

/** 补充模板可以直接进入既有目录、链接导入或内容子项目选择流程。 */
sealed interface SourceSupplementalTarget {
    data object Home : SourceSupplementalTarget
    data object Account : SourceSupplementalTarget
    data object Collections : SourceSupplementalTarget
    data class Link(val input: String = "") : SourceSupplementalTarget
    data class Collection(val collection: SourceRemoteCollection) : SourceSupplementalTarget
    data class Content(val content: SourceContent) : SourceSupplementalTarget
}

/** 自定义页面可以打开公共导航，也可以把完整公共页面嵌入自己的布局。 */
interface SourcePageUi {
    fun open(request: SourcePageRequest, onClosed: () -> Unit = {})
    /** onClose 仅请求调用方退出当前嵌入导航；业务完成与账号结果不得放在这个回调中提交。 */
    @Composable fun Content(request: SourcePageRequest, modifier: Modifier = Modifier,
        bottomInsetDp: Float = 0f, onClose: () -> Unit = {})
}
