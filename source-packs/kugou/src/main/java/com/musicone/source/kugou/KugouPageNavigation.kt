package com.musicone.source.kugou

/** 与网易云根页采用同样的首帧规则，预载页不会短暂展示另一个根页。 */
internal fun kugouVisiblePage(route: String, active: Boolean, page: MusicOnePage, root: MusicOnePage): MusicOnePage {
    val target = when (route.lowercase()) {
        "home" -> MusicOnePage.HOME
        "my", "mine" -> MusicOnePage.MY
        else -> return page
    }
    return if (!active || root != target) target else page
}
