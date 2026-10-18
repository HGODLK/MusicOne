package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B站登录认证生命周期协调器（管理扫码轮询、短信验证与极验回传）。
 */
internal class BiliLoginCoordinator(
    private val api: BiliApi,
    private val sessionStore: BiliSessionStore,
    private val state: MutableStateFlow<BiliLibraryUiState>,
    private val scope: CoroutineScope,
    private val onLoginSuccess: () -> Unit,
) {
    private var qrPollJob: Job? = null
    private var smsJob: Job? = null
    private var countdownJob: Job? = null

    fun startQrLogin(gen: Long, isCurrentGen: (Long) -> Boolean) {
        qrPollJob?.cancel()
        state.update {
            it.copy(
                qrStatus = PlatformQrLoginStatus.LOADING,
                qrMessage = null,
                error = null,
                busy = true,
                working = true,
            )
        }
        qrPollJob = scope.launch {
            try {
                val qr = withContext(Dispatchers.IO) { api.generateQrCode() }
                if (!isCurrentGen(gen)) return@launch
                state.update {
                    it.copy(
                        qrCode = qr,
                        qrStatus = PlatformQrLoginStatus.WAITING_SCAN,
                        busy = false,
                        working = false,
                    )
                }

                while (isCurrentGen(gen)) {
                    delay(2000L)
                    val poll = withContext(Dispatchers.IO) { api.pollQrCode(qr.qrcodeKey) }
                    if (!isCurrentGen(gen)) break

                    when (poll.code) {
                        0 -> {
                            val account = withContext(Dispatchers.IO) { api.getUserInfo(poll.cookie.orEmpty()) }
                            if (account != null && isCurrentGen(gen)) {
                                sessionStore.save(account)
                                state.update {
                                    it.copy(
                                        account = account,
                                        qrStatus = PlatformQrLoginStatus.SUCCESS,
                                        message = "登录成功",
                                        busy = false,
                                        working = false,
                                    )
                                }
                                onLoginSuccess()
                            }
                            break
                        }
                        86090 -> state.update {
                            it.copy(
                                qrStatus = PlatformQrLoginStatus.WAITING_CONFIRM,
                                qrMessage = poll.message,
                            )
                        }
                        86038 -> {
                            state.update {
                                it.copy(
                                    qrStatus = PlatformQrLoginStatus.EXPIRED,
                                    qrMessage = "二维码已失效，请重新生成",
                                    busy = false,
                                    working = false,
                                )
                            }
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (isCurrentGen(gen)) {
                    state.update {
                        it.copy(
                            qrStatus = PlatformQrLoginStatus.FAILED,
                            error = t.message ?: "扫码登录失败",
                            busy = false,
                            working = false,
                        )
                    }
                }
            }
        }
    }

    fun cancelQrLogin() {
        qrPollJob?.cancel()
        qrPollJob = null
        state.update {
            it.copy(
                qrStatus = PlatformQrLoginStatus.IDLE,
                qrCode = null,
                qrMessage = null,
                busy = false,
                working = false,
            )
        }
    }

    fun sendSms(cid: String, phone: String, geetest: BiliGeetestResult?, gen: Long, isCurrentGen: (Long) -> Boolean) {
        smsJob?.cancel()
        BiliAuthDiagnostics.event(if (geetest == null) "sms_start" else "sms_submit_verification")
        state.update { it.copy(busy = true, working = true, error = null, message = null,
            smsPhase = if (geetest == null) BiliSmsPhase.SENDING else BiliSmsPhase.SUBMITTING,
            smsSent = false, captchaKey = "", smsCode = "", geetestChallenge = null) }
        smsJob = scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { api.sendSmsCode(cid, phone, geetest) }) {
                    is BiliSmsSendResult.Success -> {
                        if (isCurrentGen(gen)) {
                            BiliAuthDiagnostics.event("sms_sent")
                            state.update {
                                it.copy(
                                    busy = false,
                                    working = false,
                                    smsSent = true,
                                    smsPhase = BiliSmsPhase.SENT,
                                    captchaKey = result.captchaKey,
                                    message = "验证码已发送",
                                )
                            }
                            startCountdown()
                        }
                    }
                    is BiliSmsSendResult.NeedGeetest -> {
                        if (isCurrentGen(gen)) {
                            BiliAuthDiagnostics.event("sms_need_verification")
                            state.update {
                                it.copy(
                                    busy = false,
                                    working = false,
                                    smsPhase = BiliSmsPhase.VERIFYING,
                                    message = "请完成安全验证后发送短信",
                                    geetestChallenge = result.challenge.copy(requestGeneration = gen),
                                )
                            }
                        }
                    }
                    is BiliSmsSendResult.Failed -> {
                        if (isCurrentGen(gen)) {
                            BiliAuthDiagnostics.event("sms_failed", result.code)
                            state.update {
                                it.copy(
                                    busy = false,
                                    working = false,
                                    smsPhase = BiliSmsPhase.IDLE,
                                    error = "${result.message}（${result.code}）",
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                BiliAuthDiagnostics.event("sms_cancelled")
                throw e
            } catch (t: Throwable) {
                if (isCurrentGen(gen)) {
                    BiliAuthDiagnostics.event("sms_exception")
                    state.update {
                        it.copy(
                            busy = false,
                            working = false,
                            smsPhase = BiliSmsPhase.IDLE,
                            error = t.message ?: "发送验证码失败",
                        )
                    }
                }
            }
        }
    }

    fun loginBySms(cid: String, phone: String, code: String, captchaKey: String, gen: Long, isCurrentGen: (Long) -> Boolean) {
        smsJob?.cancel()
        state.update { it.copy(busy = true, working = true, error = null, message = null, smsPhase = BiliSmsPhase.LOGGING_IN) }
        smsJob = scope.launch {
            try {
                val account = withContext(Dispatchers.IO) { api.loginBySms(cid, phone, code, captchaKey) }
                if (isCurrentGen(gen)) {
                    sessionStore.save(account)
                    state.update {
                        it.copy(
                            busy = false,
                            working = false,
                            account = account,
                            message = "登录成功",
                            smsCode = "",
                            captchaKey = "",
                            smsPhase = BiliSmsPhase.IDLE,
                        )
                    }
                    onLoginSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (isCurrentGen(gen)) {
                    state.update {
                        it.copy(
                            busy = false,
                            working = false,
                            error = t.message ?: "登录失败",
                            smsPhase = if (it.smsSent) BiliSmsPhase.SENT else BiliSmsPhase.IDLE,
                        )
                    }
                }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (sec in 60 downTo 0) {
                state.update { it.copy(resendSeconds = sec) }
                if (sec > 0) delay(1000L)
            }
        }
    }

    fun cancelAllJobs() {
        qrPollJob?.cancel()
        qrPollJob = null
        smsJob?.cancel()
        smsJob = null
        countdownJob?.cancel()
        countdownJob = null
    }

    /**
     * 退出页面时调用：停掉二维码、短信及倒计时任务，清空临时验证上下文与验证码输入，但保留登录会话。
     */
    fun cancelOnExit() {
        cancelAllJobs()
        state.update {
            it.copy(
                qrCode = null,
                qrStatus = PlatformQrLoginStatus.IDLE,
                qrMessage = null,
                smsCode = "",
                captchaKey = "",
                geetestChallenge = null,
                resendSeconds = 0,
                smsSent = false,
                smsPhase = BiliSmsPhase.IDLE,
                busy = false,
                working = false,
            )
        }
    }

    fun logout() {
        sessionStore.clear()
        cancelAllJobs()
        state.update {
            it.copy(
                account = null,
                selectedFolder = null,
                smsCode = "",
                captchaKey = "",
                geetestChallenge = null,
                resendSeconds = 0,
                smsSent = false,
                smsPhase = BiliSmsPhase.IDLE,
                qrCode = null,
                qrStatus = PlatformQrLoginStatus.IDLE,
                qrMessage = null,
                busy = false,
                working = false,
                message = "已退出登录",
            )
        }
    }
}
