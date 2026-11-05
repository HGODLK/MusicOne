package com.musicone.source.subsonic

import com.musicone.source.sdk.SourceOperationException
import com.musicone.source.subsonic.internal.SubsonicServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 所有网络请求的统一入口：在 IO 线程执行，换算公开错误，
 * 账号被拒时标记失效；请求期间切换了服务器则丢弃旧结果。
 */
internal class SubsonicRequests(private val auth: SubsonicAuthentication) {
    /** 针对当前服务器的资料请求（首页、搜索、我的等）。 */
    suspend fun <T> current(work: (SubsonicServer) -> T): T = withContext(Dispatchers.IO) {
        val revision = auth.revision
        val server = auth.current() ?: throw subsonicLoginRequired()
        val result = run(server, work)
        currentCoroutineContext().ensureActive()
        if (auth.revision != revision && auth.current()?.key != server.key) throw CancellationException("服务器已切换，旧结果已取消")
        result
    }

    /**
     * 针对某首歌或某个集合所属服务器的请求（播放、歌词、收藏）。
     * 队列里可能有切换前的歌，只要那台服务器还保存着，就用它自己的凭据。
     */
    suspend fun <T> forServer(key: String?, work: (SubsonicServer) -> T): T = withContext(Dispatchers.IO) {
        val server = (key?.let(auth::server) ?: auth.current().takeIf { key == null })
            ?: throw SourceOperationException("AUTH_REQUIRED", "这首歌所属的服务器已删除，请重新添加该服务器")
        run(server, work)
    }

    private suspend fun <T> run(server: SubsonicServer, work: (SubsonicServer) -> T): T = try {
        work(server)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error.rejectsSubsonicCredential) auth.markExpired(server.key)
        throw error.toSubsonicSdkFailure()
    }
}
