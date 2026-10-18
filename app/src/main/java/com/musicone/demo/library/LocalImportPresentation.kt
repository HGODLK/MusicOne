package com.musicone.demo

/** 首页仅保留导入统计，文件名和技术异常由日志保存。 */
internal fun LocalImportResult.displaySummary(): String = buildList {
    add("已导入 $added 首")
    add("已存在 $duplicates 首")
    if (failed.isNotEmpty()) add("${failed.size} 项未导入")
}.joinToString(" · ")
