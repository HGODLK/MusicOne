package com.musicone

import com.musicone.source.sdk.SourceLibrarySection

// “我的歌单”分区编号沿用原顺序，切换动画按编号大小决定方向。
internal const val MY_SECTION_CREATED = 0
internal const val MY_SECTION_SAVED = 1
internal const val MY_SECTION_LOCAL = 2

/** 远端分区只在音源声明对应资料库时显示；本地分区始终保留。 */
internal fun mainSourceLibrarySections(declared: Collection<SourceLibrarySection>): List<Int> = buildList {
    if (SourceLibrarySection.CREATED_COLLECTIONS in declared) add(MY_SECTION_CREATED)
    if (SourceLibrarySection.SAVED_COLLECTIONS in declared) add(MY_SECTION_SAVED)
    add(MY_SECTION_LOCAL)
}

/** 保存的分区不再可用时（换了音源），回到第一个可用分区。 */
internal fun List<Int>.resolveSection(saved: Int): Int = if (saved in this) saved else first()
