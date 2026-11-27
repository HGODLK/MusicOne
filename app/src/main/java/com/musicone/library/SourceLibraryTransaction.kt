package com.musicone

import android.database.sqlite.SQLiteDatabase
import com.musicone.source.runtime.SourceCommitGuard
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** 在数据库事务提交前再核对一次音源实例和协程取消状态，失效就直接回滚不提交。 */
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
