package com.musicone.demo

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
        var attemptedRefresh = false
        if (shouldRefresh(credential, nowSeconds)) {
            attemptedRefresh = true
            try {
                credential = refresh(session.deviceId, credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 当前票据可能仍有效，继续向服务端核实。
            }
        }
        val verifiedAccount = try {
            account(credential)
        } catch (error: PlatformApiException) {
            if (attemptedRefresh || error.apiCode != 301) throw error
            val renewed = try {
                refresh(session.deviceId, credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw error
            }
            val renewedAccount = account(renewed)
            credential = renewed
            renewedAccount
        }
        return QqValidatedSession(credential, verifiedAccount)
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
