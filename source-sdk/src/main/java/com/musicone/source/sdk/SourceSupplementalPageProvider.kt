package com.musicone.source.sdk

import androidx.compose.runtime.Composable

/** API 1 补充音源按可选页面接口声明逐页页面策略。 */
object SourceSupplementalContract { const val API_VERSION = 1 }
enum class SourceSupplementalPage { HOME, LINK, CONTENT, COLLECTIONS, COLLECTION, ACCOUNT, SETTINGS }
enum class SourceSupplementalPageMode { DEFAULT, CUSTOM, DISABLED }
data class SourceSupplementalPagePolicy(
    val mode: SourceSupplementalPageMode = SourceSupplementalPageMode.DEFAULT,
    val allowPublicFallback: Boolean = false,
)

/** 守卫入口透明转发可选能力；空值表示未提供该能力。 */
interface SourceSupplementalPageProviderAccess {
    val supplementalPageProvider: SourceSupplementalPageProvider?
}
fun SourceEntry.supplementalPageProviderOrNull(): SourceSupplementalPageProvider? =
    (this as? SourceSupplementalPageProviderAccess)?.supplementalPageProvider ?: this as? SourceSupplementalPageProvider

interface SourceSupplementalPageProvider {
    fun supplementalPagePolicy(page: SourceSupplementalPage): SourceSupplementalPagePolicy = SourceSupplementalPagePolicy()
    /** 仅无登录账号的公开订阅使用。必须稳定、非空且与该源其他身份隔离；不会创建假账号。 */
    fun publicSubscriptionIdentity(): String? = null
    @Composable fun SupplementalPage(context: SourceSupplementalPageContext)
}

/** 自定义整页与公共整页共用业务规则；展示失败只由显式回调报告。 */
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

/** 动作沿用公共请求、选择、导入及订阅规则，异步结果通过当前页面状态显示。 */
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
