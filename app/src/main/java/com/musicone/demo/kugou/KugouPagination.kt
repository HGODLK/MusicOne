package com.musicone.demo

internal fun <T> collectKugouPages(
    pageSize: Int,
    pageIdentity: (T) -> String,
    loadPage: (page: Int, pageSize: Int) -> List<T>,
): List<T> {
    require(pageSize > 0)
    val result = mutableListOf<T>()
    val seenPages = mutableSetOf<List<String>>()
    var page = 1
    while (true) {
        val items = loadPage(page, pageSize)
        if (items.isEmpty()) break
        val signature = items.map(pageIdentity).sorted()
        if (signature.all(String::isNotBlank) && !seenPages.add(signature)) break
        result += items
        if (items.size < pageSize) break
        page++
    }
    return result
}
