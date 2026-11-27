package com.musicone.source.qq.internal

import kotlinx.coroutines.CancellationException

internal data class QqValidatedSession(val credential: String, val account: MusicAccount)

/** 使用登录接口返回的有效期提前续期；服务端拒绝旧票据时再补试一次。 */
internal class QqPersistentSession(
    private val refresh: (String, String) -> String = { deviceId, credential ->
        QqSmsLoginClient().refreshCredential(deviceId, credential)
    },
    private val account: (String) -> MusicAccount = QqApiClient()::account,
) {
    fun validate(session: PlatformSession, nowSeconds: Long = System.currentTimeMillis() / 1_000L): QqValidatedSession {
        var credential = session.credential
        var refreshFailure: Exception? = null
        val attemptedRefresh = shouldRefresh(credential, nowSeconds)
        if (attemptedRefresh) {
            try {
                credential = refreshLogged(session, credential, "到期前续期")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 当前票据可能仍有效，继续向服务端核实。
                refreshFailure = failure
            }
        }
        val verifiedAccount = try {
            account(credential)
        } catch (error: PlatformApiException) {
            if (!error.rejectsQqCredential()) throw error
            // 续期遇到网络、风控或限流错误时当作临时故障抛出去，不要误判成登录失效
            refreshFailure?.let { if (!it.rejectsQqCredential()) throw it }
            if (attemptedRefresh) throw error
            val renewed = try {
                refreshLogged(session, credential, "校验被拒后续期")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw if (failure.rejectsQqCredential()) error else failure
            }
            val renewedAccount = account(renewed)
            credential = renewed
            renewedAccount
        }
        return QqValidatedSession(credential, verifiedAccount)
    }

    /** 服务端拒了当前票据时调用：不管过期时间，直接强制换新票并校验账号。 */
    fun renew(session: PlatformSession): QqValidatedSession {
        val renewed = refreshLogged(session, session.credential, "请求被拒后续期")
        return QqValidatedSession(renewed, account(renewed))
    }

    private fun refreshLogged(session: PlatformSession, credential: String, reason: String): String {
        val started = System.currentTimeMillis()
        return try {
            refresh(session.deviceId, credential).also {
                QqSessionLog.record("续票成功：$reason，耗时=${System.currentTimeMillis() - started}ms")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            QqSessionLog.record("续票失败：$reason，${failure.qqLogSummary()}，耗时=${System.currentTimeMillis() - started}ms")
            throw failure
        }
    }

    companion object {
        fun shouldRefresh(credential: String, nowSeconds: Long = System.currentTimeMillis() / 1_000L): Boolean {
            val values = credential.cookieValues()
            val created = values["musickeyCreateTime"]?.toLongOrNull() ?: return false
            val lifetime = values["keyExpiresIn"]?.toLongOrNull() ?: return false
            if (created <= 0L || lifetime <= 0L) return false
            return nowSeconds >= created + lifetime - 600L
        }
    }
}
