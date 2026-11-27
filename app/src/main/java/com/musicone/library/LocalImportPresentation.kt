package com.musicone

/** 界面提示只显示导入了多少首，具体报错细节打日志。 */
internal fun LocalImportResult.displaySummary(): String = buildList {
    add("已导入 $added 首")
    add("已存在 $duplicates 首")
    if (failed.isNotEmpty()) add("${failed.size} 项未导入")
}.joinToString(" · ")
