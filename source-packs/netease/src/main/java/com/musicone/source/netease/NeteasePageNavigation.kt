package com.musicone.source.netease

import androidx.compose.runtime.*

/** 根页切换立即选定首帧，内部返回栈不再决定其他预载根页的显示。 */
internal class NeteasePageNavigation(private val onChanged: (String) -> Unit) {
    var page by mutableStateOf("home")
        private set
    var root by mutableStateOf("home")
        private set
    private val history = ArrayDeque<String>()

    fun replace(value: String) { page = value; onChanged(value) }
    fun open(value: String) { if (page != value) { history.addLast(page); replace(value) } }
    fun primary(value: String) {
        history.clear()
        if (value == "home" || value == "my") root = value
        replace(value)
    }
    fun back(): Boolean {
        if (history.isEmpty()) return false
        replace(history.removeLast())
        return true
    }
    fun visible(routePage: String, active: Boolean): String = neteaseVisiblePage(routePage, active, page, root)
}

internal fun neteaseVisiblePage(routePage: String, active: Boolean, page: String, root: String): String =
    if (!active || routePage in setOf("home", "my") && root != routePage) routePage else page
