package com.musicone.source.kugou

/** 和网易云一致的根页面可见性判断，避免预加载的 Tab 短暂闪出别的页面。 */
internal fun kugouVisiblePage(route: String, active: Boolean, page: MusicOnePage, root: MusicOnePage): MusicOnePage {
    val target = when (route.lowercase()) {
        "home" -> MusicOnePage.HOME
        "my", "mine" -> MusicOnePage.MY
        else -> return page
    }
    return if (!active || root != target) target else page
}
