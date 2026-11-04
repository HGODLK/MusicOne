package com.musicone

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourceApplicationHost
import com.musicone.sourceintegration.SourcePackageController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun refreshLibrarySubscription(context: Context, store: MusicLibraryStore, playlistId: String): Int {
    val subscription = store.state.value.subscriptions.firstOrNull { it.playlistId == playlistId } ?: return 0
    val instance = SourcePackageController.get(context).running(subscription.sourceId) ?: error("订阅音源未激活，请重新导入后重启")
    return instance.withOperation {
        val entry = instance.entry
        val account = entry.authentication?.currentAccount()
        if (entry.supplementalPageProviderOrNull() == null && account == null) error("请先登录订阅所属音源")
        val accountId = supplementalSubscriptionAccountId(entry, account?.account)
        require(accountId == subscription.accountId) { "请登录订阅所属账号后刷新" }
        val service = entry.subscriptions ?: error("此音源未提供订阅")
        val collections = entry.remoteCollections ?: error("此音源未提供收藏夹目录")
        val folder = collectPages { cursor -> collections.collections(SourceCursorRequest(cursor)) }
            .firstOrNull { it.identity.remoteId == subscription.remoteCollectionId } ?: error("订阅的收藏夹已不存在")
        val library = SourceApplicationHost.get(context).library
        val identity = SourceSubscriptionIdentity(subscription.sourceId, subscription.accountId, subscription.remoteCollectionId, playlistId)
        val expected = library.subscriptionRevision(identity)
        val items = collectPages { cursor -> service.page(folder, SourceCursorRequest(cursor)) }
        currentCoroutineContext().ensureActive()
        check(entry.authentication?.currentAccount() == account) { "账号已变化，请重新刷新" }
        check(supplementalSubscriptionAccountId(entry, entry.authentication?.currentAccount()?.account) == accountId) { "订阅身份已变化，请重新刷新" }
        library.commitSubscription(identity, expected, items)
        items.sumOf { it.tracks.size }
    }
}
