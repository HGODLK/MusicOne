package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 嵌入子页只往外汇报当前标题，返回按钮继续复用最外层导航栏。 */
internal class SupplementalEmbeddedPageHeader {
    private var owner: Any? = null
    private var back: (() -> Boolean)? = null
    var title by mutableStateOf<String?>(null)
        private set
    private fun adopt(owner: Any) {
        if (this.owner !== owner) { this.owner = owner; back = null }
    }
    fun update(owner: Any, title: String) { adopt(owner); this.title = title }
    fun updateBack(owner: Any, back: () -> Boolean) { adopt(owner); this.back = back }
    fun back(): Boolean = back?.invoke() == true
    fun clear(owner: Any) {
        if (this.owner === owner) clear()
    }
    fun clear() { owner = null; title = null; back = null }
}

internal fun supplementalPageTitle(state: SupplementalState, sourceName: String): String = when (state.page) {
    SupplementalPage.HOME -> sourceName
    SupplementalPage.ACCOUNT -> if (state.account == null) "登录$sourceName" else "账号信息"
    SupplementalPage.LINK -> "链接导入"
    SupplementalPage.FOLDERS -> "音乐收藏夹"
    SupplementalPage.FOLDER -> state.selectedCollection?.title ?: "收藏夹"
    SupplementalPage.CONTENT -> "选择子项目"
    SupplementalPage.SETTINGS -> "音源设置"
}
