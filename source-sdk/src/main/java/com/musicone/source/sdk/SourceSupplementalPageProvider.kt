package com.musicone.source.sdk

import androidx.compose.runtime.Composable

/** API 1 补充音源可按页面分别声明使用公共页还是自定义页。 */
object SourceSupplementalContract { const val API_VERSION = 1 }
enum class SourceSupplementalPage { HOME, LINK, CONTENT, COLLECTIONS, COLLECTION, ACCOUNT, SETTINGS }
enum class SourceSupplementalPageMode { DEFAULT, CUSTOM, DISABLED }
data class SourceSupplementalPagePolicy(
    val mode: SourceSupplementalPageMode = SourceSupplementalPageMode.DEFAULT,
    val allowPublicFallback: Boolean = false,
)

/** 透传可选能力，为 null 表示未实现。 */
interface SourceSupplementalPageProviderAccess {
    val supplementalPageProvider: SourceSupplementalPageProvider?
}
fun SourceEntry.supplementalPageProviderOrNull(): SourceSupplementalPageProvider? =
    (this as? SourceSupplementalPageProviderAccess)?.supplementalPageProvider ?: this as? SourceSupplementalPageProvider

interface SourceSupplementalPageProvider {
    fun supplementalPagePolicy(page: SourceSupplementalPage): SourceSupplementalPagePolicy = SourceSupplementalPagePolicy()
    /** 仅用于免登录的公开订阅；须固定、非空且与该源其他账号隔离，不会创建虚拟账号。 */
    fun publicSubscriptionIdentity(): String? = null
    @Composable fun SupplementalPage(context: SourceSupplementalPageContext)
}

/** 自定义页和公共页共用同一套业务规则；展示出错时通过回调显式上报。 */
interface SourceSupplementalPageContext {
    val page: SourceSupplementalPage
    val target: SourceSupplementalTarget
    val bottomInsetDp: Float
    val actions: SourceSupplementalPageActions
    fun onBack()
    fun onFailure(message: String)
    fun openPublic(page: SourceSupplementalPage, target: SourceSupplementalTarget = SourceSupplementalTarget.Home)
    @Composable fun PublicContent(page: SourceSupplementalPage = this.page,
        target: SourceSupplementalTarget = this.target)
}

/** 复用公共的请求、选择、导入和订阅流程，异步结果直接反映到当前页面状态。 */
interface SourceSupplementalPageActions {
    fun navigate(page: SourceSupplementalPage, target: SourceSupplementalTarget = SourceSupplementalTarget.Home)
    fun resolve(input: String)
    fun openContent(content: SourceContent)
    fun play(contents: List<SourceContent>)
    fun importContents(contents: List<SourceContent>, destinationId: String, destinationTitle: String)
    fun subscribe(collection: SourceRemoteCollection)
    fun refreshSubscription(collection: SourceRemoteCollection)
    fun removeSubscription(collection: SourceRemoteCollection)
}
