package com.musicone.source.kugou

import com.musicone.source.sdk.SourceOperationException
import kotlinx.coroutines.CancellationException

internal fun kugouCreateUnconfirmed() = SourceOperationException("COLLECTION_CREATE_UNCONFIRMED",
    "酷狗已接收创建请求，但尚不能唯一确认新增歌单，请刷新资料库确认，勿重复创建")

/** 写入已成功后，回读失败也必须进入公共表单已有的待确认状态。 */
internal suspend fun <T> confirmKugouCreatedCollection(read: suspend () -> T): T = try { read() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { throw kugouCreateUnconfirmed() }
