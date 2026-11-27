package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** 监听音源的歌单变动通知，只刷新有变动的分区，不强刷整个列表引发循环请求。 */
internal fun CoroutineScope.observeSourceLibraryChanges(service: SourceCollections?, accountId: String,
    current: () -> Boolean, refresh: (Set<SourceLibrarySection>) -> Unit) = launch {
    try {
        (service as? SourceLibraryChanges)?.libraryChanges?.collect { change ->
            if (current() && change.accountId == accountId) refresh(change.sections)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* 通知失败不覆盖已有内容；手动刷新仍走原错误提示。 */ }
}
