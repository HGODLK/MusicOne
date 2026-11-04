package com.musicone.source.runtime

import com.musicone.source.sdk.*

/** 新能力按可选接口校验；未使用接口的旧包不补默认服务，也不改变缺省页面行为。 */
internal object SourceOptionalContractValidator {
    fun check(entry: SourceEntry) {
        val pages = entry.supplementalPageProviderOrNull()
        val catalog = entry.mediaPlayback as? SourceQualityCatalog
        val player = entry.playerUiProviderOrNull()
        if (pages == null && catalog == null && player == null) return
        if (player != null) {
            require(entry.descriptor.kind == SourceKind.MAIN) { "自建播放器只能用于主音源" }
            val declaration = player.playerUiDeclaration
            require(declaration.supportsPhone || declaration.supportsTablet) { "自建播放器至少支持一种设备" }
            require(!declaration.supportsPhoneLandscape || declaration.supportsPhone) { "手机横屏支持依赖手机支持" }
            require(declaration.opening.durationMillis >= 0) { "播放器开页时长不能为负数" }
            require(declaration.opening.barParticipates || declaration.opening.sharedElements.isEmpty()) { "共享元素要求宿主底部栏参与开页" }
        }
        if (pages != null) {
            require(entry.descriptor.kind == SourceKind.SUPPLEMENTAL) { "补充页面契约只能用于补充音源" }
            require(!entry.descriptor.useCustomPages) { "逐页接管由页面策略选择，请关闭旧的整包自定义开关" }
            SourceSupplementalPage.entries.forEach { page ->
                val policy = pages.supplementalPagePolicy(page)
                require(!policy.allowPublicFallback || policy.mode == SourceSupplementalPageMode.CUSTOM) { "只有自定义页面可以声明公共页故障降级" }
            }
            val public = pages.publicSubscriptionIdentity()
            require(public == null || public.isNotBlank()) { "公开订阅身份不能为空白" }
        }
        if (catalog != null) {
            val definitions = catalog.qualityCatalog
            val ids = definitions.map { it.id }
            require(ids.isNotEmpty() && ids.all(String::isNotBlank) && ids.distinct().size == ids.size) { "音质目录必须包含唯一非空 ID" }
            val standardIds = setOf("STANDARD", "HIGHER", "EXHIGH", "LOSSLESS", "HI_RES", "HIRES", "DOLBY")
            require(definitions.all { it.standardQualityId?.uppercase()?.let(standardIds::contains) ?: true }) { "音质标准映射无效" }
            require(catalog.defaultCatalogQualityId in ids) { "默认音质不在目录中" }
            ids.forEach { id ->
                val fallback = catalog.catalogFallbackQualityIds(id)
                require(fallback.all { it in ids }) { "音质回退只能包含目录内的 ID" }
            }
        }
    }
}
