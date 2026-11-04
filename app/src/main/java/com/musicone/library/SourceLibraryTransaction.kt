package com.musicone

import android.database.sqlite.SQLiteDatabase
import com.musicone.source.runtime.SourceCommitGuard
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** 数据写完后再校验请求身份；失效或取消时回滚，保留刷新前的订阅内容。 */
internal fun <T> SQLiteDatabase.inSourceTransaction(context: CoroutineContext, block: () -> T): T {
    val guard = context[SourceCommitGuard]
    guard?.check()
    beginTransaction()
    var ended = false
    try {
        val result = block()
        val commit = {
            context.ensureActive()
            setTransactionSuccessful()
            endTransaction()
            ended = true
        }
        if (guard == null) commit() else guard.commit(commit)
        return result
    } finally {
        if (!ended) endTransaction()
    }
}
