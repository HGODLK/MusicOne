package com.musicone.demo

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

/** 一级页面离开首页前先关闭搜索并清空输入，保留历史但不在“我的”重开。 */
internal class QqSearchHomeGuard(
    private val model: QqSearchViewModel,
    private val motion: QqSearchMotion,
) {
    fun onBottomBarContact() = model.close()

    fun blocksBottomBarDrag(): Boolean = motion.mounted

    suspend fun beforePageChange(target: MusicOnePage) {
        if (target != MusicOnePage.HOME) {
            model.close()
            snapshotFlow { !motion.mounted }.first { it }
        }
    }
}

@Composable
internal fun rememberQqSearchHomeGuard(page: MusicOnePage, model: QqSearchViewModel,
    motion: QqSearchMotion): QqSearchHomeGuard {
    LaunchedEffect(page) { if (page != MusicOnePage.HOME) model.close() }
    return remember(model, motion) { QqSearchHomeGuard(model, motion) }
}
