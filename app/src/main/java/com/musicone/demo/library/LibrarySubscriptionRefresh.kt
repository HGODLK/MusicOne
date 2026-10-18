package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

internal suspend fun refreshLibrarySubscription(context: Context, store: MusicLibraryStore, playlistId: String): Int {
    val account = BiliSessionStore(context).read() ?: error("请在补充音源设置登录B站账号")
    val session = BiliSessionStore(context)
    val subscription = store.state.value.subscriptions.firstOrNull { it.playlistId == playlistId } ?: return 0
    val job = currentCoroutineContext().job
    return BiliSubscriptionSyncer(BiliApi(context), store).syncSubscription(playlistId, account) {
        !job.isActive || session.read() != account || store.state.value.subscriptions.none { it == subscription }
    }
}
