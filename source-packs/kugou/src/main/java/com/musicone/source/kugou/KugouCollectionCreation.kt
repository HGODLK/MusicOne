package com.musicone.source.kugou

import com.musicone.source.sdk.SourceOperationException
import kotlinx.coroutines.CancellationException

internal fun kugouCreateUnconfirmed() = SourceOperationException("COLLECTION_CREATE_UNCONFIRMED",
    "酷狗已接收创建请求，但尚不能唯一确认新增歌单，请刷新资料库确认，勿重复创建")

/** 创建歌单请求已经成功，如果紧接着回读列表失败，抛异常让表单进入待确认状态。 */
internal suspend fun <T> confirmKugouCreatedCollection(read: suspend () -> T): T = try { read() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { throw kugouCreateUnconfirmed() }
