package com.musicone.source.sdk

/** discovery 可选实现的我的页分区声明；未实现时保留公共模板原有分区。 */
interface SourceMySectionPresentation {
    val showRecentInMy: Boolean
}
