package com.musicone.demo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 扫码任务独立于设置页；取消或换码后丢弃旧请求的所有迟到结果。 */
internal class PlatformQrLoginController(
    private val scope: CoroutineScope,
    private val create: suspend (MusicSource, PlatformLoginMethod, String) -> PlatformQrChallenge,
    private val check: suspend (PlatformQrChallenge) -> PlatformQrResult,
    private val onChanged: (PlatformQrLoginStatus, String?, PlatformQrChallenge?) -> Unit,
    private val onSuccess: (String, MusicAccount) -> Unit,
    private val onSecurity: (Throwable, Boolean) -> Boolean,
    private val pollIntervalMs: Long = 2_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private var generation = 0L
    private var pending: PlatformQrChallenge? = null
    private var deadline = 0L

    fun start(source: MusicSource, method: PlatformLoginMethod, deviceId: String, verificationCredential: String = "") {
        stop()
        val ticket = generation
        onChanged(PlatformQrLoginStatus.LOADING, null, null)
        job = scope.launch {
            val challenge = try {
                create(source, method, deviceId).copy(verificationCredential = verificationCredential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                Log.e("PlatformQrLogin", "扫码创建失败: source=$source, method=$method, msg=${error.message}", error)
                if (generation == ticket && !onSecurity(error, false)) {
                    onChanged(PlatformQrLoginStatus.FAILED, error.asUserMessage(), null)
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            if (generation != ticket) return@launch
            pending = challenge
            deadline = nowMillis() + 180_000L
            onChanged(PlatformQrLoginStatus.WAITING_SCAN, challenge.scanMessage.ifBlank { "请使用${source.label} App 扫码" }, challenge)
            poll(ticket)
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        pending = null
    }

    fun mergeVerificationCredential(credential: String) {
        pending = pending?.let { it.copy(credentialSeed = mergePlatformCredentials(it.credentialSeed, credential)) }
    }

    fun restartAfterVerification(credential: String): Boolean {
        val challenge = pending ?: return false
        start(challenge.source, challenge.loginMethod, challenge.deviceId,
            mergePlatformCredentials(challenge.verificationCredential, credential))
        return true
    }

    fun resume() {
        if (pending == null) return
        job?.cancel()
        val ticket = ++generation
        job = scope.launch { poll(ticket) }
    }

    private suspend fun poll(ticket: Long) {
        var scanned = false
        while (generation == ticket) {
            delay(pollIntervalMs)
            val challenge = pending ?: return
            if (nowMillis() >= deadline) {
                onChanged(PlatformQrLoginStatus.EXPIRED, "二维码已过期，请重新生成", null)
                pending = null
                return
            }
            val result = try {
                check(challenge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                Log.e("PlatformQrLogin", "扫码轮询失败: source=${challenge.source}, method=${challenge.loginMethod}, msg=${error.message}", error)
                if (generation == ticket && !onSecurity(error, true)) {
                    onChanged(PlatformQrLoginStatus.FAILED, error.asUserMessage(), null)
                }
                return
            }
            currentCoroutineContext().ensureActive()
            if (generation != ticket) return
            // 微信长轮询超时不会撤销此前已扫码的提示。
            val status = if (scanned && result.status == PlatformQrLoginStatus.WAITING_SCAN) {
                PlatformQrLoginStatus.WAITING_CONFIRM
            } else result.status
            val message = if (status != result.status) "已扫码，请在手机上确认登录" else result.message
            scanned = scanned || status == PlatformQrLoginStatus.WAITING_CONFIRM
            pending = challenge.copy(credentialSeed = result.credential)
            onChanged(status, message, pending)
            when (status) {
                PlatformQrLoginStatus.EXPIRED, PlatformQrLoginStatus.FAILED -> { pending = null; return }
                PlatformQrLoginStatus.SUCCESS -> {
                    pending = null
                    val account = result.account
                    if (account == null || result.credential.isBlank() || account.source != challenge.source) {
                        onChanged(PlatformQrLoginStatus.FAILED, "平台未返回完整登录结果，请重新生成二维码", null)
                    } else {
                        onSuccess(result.credential, account)
                    }
                    return
                }
                else -> Unit
            }
        }
    }
}
