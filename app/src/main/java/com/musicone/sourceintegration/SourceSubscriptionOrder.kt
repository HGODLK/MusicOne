package com.musicone.sourceintegration

internal data class SourceSubscriptionOrder(
    val remotePositions: Map<String, Long>,
    val manualPositions: Map<String, Long>,
) {
    // 成功刷新恢复远端顺序；手动排序标记只影响刷新前的本地展示。
    val manualOrder: Boolean = false
}

internal fun sourceSubscriptionOrder(remoteIds: List<String>, manualIds: List<String>): SourceSubscriptionOrder {
    val remote = remoteIds.distinct()
    return SourceSubscriptionOrder(
        remote.mapIndexed { index, id -> id to index.toLong() }.toMap(),
        manualIds.distinct().mapIndexed { index, id -> id to (remote.size + index).toLong() }.toMap(),
    )
}
