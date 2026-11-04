package com.musicone.source.qq

import androidx.compose.runtime.*
import com.musicone.source.sdk.*
import com.musicone.source.qq.internal.*
import kotlinx.coroutines.*

/** 包内只装配既有登录页与认证服务；页面布局复用 0.2.0。 */
internal class QqPackagePages(private val auth: QqAuthentication) : SourceAuthenticationPage {
    @Composable override fun Content() {
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf(QqLoginUiState()) }
        var challenge by remember { mutableStateOf<SourceAuthChallenge?>(null) }
        var loginVerification by remember { mutableStateOf<PlatformSecurityChallenge?>(null) }
        var retry by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
        val playbackVerification by QqSessionRequestCoordinator.verification.collectAsState()
        var playbackPage by remember { mutableStateOf(playbackVerification != null) }
        val latestPlayback by rememberUpdatedState(playbackVerification?.challenge)
        var job by remember { mutableStateOf<Job?>(null) }
        var operationRevision by remember { mutableLongStateOf(0L) }
        var qrVerification by remember { mutableStateOf(false) }
        val pageCloseRetry = remember { QqPageCloseRetryGuard() }
        val qrController = remember(scope) {
            auth.qrController(scope, onChanged = { status, text, value ->
                state = state.copy(working = status == PlatformQrLoginStatus.LOADING,
                    qrStatus = status, qrMessage = text,
                    qrImageBase64 = value?.imageBase64.orEmpty(), qrUrl = value?.url.orEmpty())
            }, onSuccess = {
                state = state.copy(working = false, message = "登录成功", qrStatus = PlatformQrLoginStatus.SUCCESS,
                    qrMessage = "登录成功", qrImageBase64 = "", qrUrl = "")
                auth.loginCompleted()
            }, onSecurity = { error, _ ->
                if (error is PlatformSecurityVerificationRequired) {
                    loginVerification = error.challenge
                    qrVerification = true
                    state = state.copy(working = false)
                    true
                } else false
            })
        }
        fun startQr(method: PlatformLoginMethod = state.loginMethod) {
            pageCloseRetry.reset()
            operationRevision++
            job?.cancel()
            auth.cancelChallenges()
            challenge = null
            loginVerification = null
            retry = null
            qrVerification = false
            qrController.start(MusicSource.QQ, method, auth.session().deviceId)
        }
        fun execute(block: suspend () -> Unit) {
            qrController.stop()
            qrVerification = false
            job?.cancel()
            val revision = ++operationRevision
            state = state.copy(working = true, message = null)
            job = scope.launch {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (required: PlatformSecurityVerificationRequired) { loginVerification = required.challenge; retry = block }
                catch (error: Exception) { state = state.copy(message = error.message ?: "QQ请求失败，请重试") }
                finally { if (operationRevision == revision) state = state.copy(working = false) }
            }
        }
        fun clearSmsChallenge() {
            state = state.copy(working = false)
            operationRevision++
            job?.cancel()
            auth.cancelChallenges()
            challenge = null
            loginVerification = null
            retry = null
        }
        DisposableEffect(qrController) { onDispose {
            qrController.stop()
            operationRevision++
            job?.cancel()
            auth.cancelChallenges()
            latestPlayback?.let { QqSessionRequestCoordinator.cancel(it) }
        } }
        LaunchedEffect(playbackVerification) {
            if (playbackVerification != null) playbackPage = true
            else if (playbackPage && loginVerification == null) auth.verificationClosed()
        }
        LaunchedEffect(state.resendSeconds > 0) {
            while (state.resendSeconds > 0) {
                delay(1_000L)
                state = state.copy(resendSeconds = (state.resendSeconds - 1).coerceAtLeast(0))
            }
        }
        val required = loginVerification ?: playbackVerification?.challenge
        if (required == null && !playbackPage) QqLoginScreen(state, QqLoginActions(
            onSelectLoginMethod = { method ->
                if (method != state.loginMethod) {
                    clearSmsChallenge()
                    qrController.stop()
                    qrVerification = false
                    state = QqLoginUiState(loginMethod = method, phone = state.phone, countryCode = state.countryCode)
                    if (method != PlatformLoginMethod.SMS) startQr(method)
                }
            },
            onCountryCodeChange = {
                val updated = state.changeCountry(it)
                if (updated != state) { clearSmsChallenge(); state = updated.copy(working = false) }
            },
            onPhoneChange = {
                val updated = state.changePhone(it)
                if (updated != state) { clearSmsChallenge(); state = updated.copy(working = false) }
            },
            onCaptchaChange = { state = state.copy(captcha = it.filter(Char::isDigit).take(10), message = null) },
            onSendCaptcha = {
                if (state.canSendCaptcha) {
                    pageCloseRetry.reset()
                    val phone = state.phone
                    val country = state.countryCode
                    execute {
                        challenge = auth.begin("sms", mapOf("phone" to phone, "countryCode" to country))
                        state = state.copy(captchaSent = true, resendSeconds = 60, message = challenge?.message)
                    }
                }
            },
            onLogin = {
                val current = challenge
                if (state.canLogin && current?.kind == "sms") {
                    pageCloseRetry.reset()
                    val code = state.captcha
                    execute {
                        val result = auth.continueAuthentication(current, mapOf("code" to code))
                        state = state.copy(message = result.message)
                        if (result.progress == SourceAuthProgress.SUCCEEDED) {
                            state = state.copy(captchaSent = false)
                            challenge = null
                            auth.loginCompleted()
                        } else if (result.challenge != null) challenge = result.challenge
                    }
                }
            },
            onStartQrLogin = { startQr() },
        ), reduceMotion = auth.reduceMotion)
        required?.let { verification ->
            val verifyingPlayback = loginVerification == null
            fun isCurrentVerification(): Boolean = (if (verifyingPlayback)
                QqSessionRequestCoordinator.verification.value?.challenge else loginVerification) === verification
            val verificationState = remember(verification, playbackVerification?.instanceId) { verification.toUiState() }
            fun cancelVerification(message: String) {
                operationRevision++
                job?.cancel()
                state = state.copy(working = false, message = message)
                loginVerification = null
                retry = null
                if (qrVerification) {
                    qrController.stop()
                    qrVerification = false
                    state = state.copy(qrStatus = PlatformQrLoginStatus.FAILED, qrMessage = "已取消验证，请重新生成二维码",
                        qrImageBase64 = "", qrUrl = "")
                }
                if (verifyingPlayback) QqSessionRequestCoordinator.cancel(verification)
            }
            QqVerificationDialog(verificationState, state.working, state.message, onCancel = {
                if (!isCurrentVerification()) return@QqVerificationDialog
                cancelVerification("已取消验证，可重新尝试")
            }, onComplete = { result ->
                if (!isCurrentVerification()) return@QqVerificationDialog
                // 播放验证的单次重试由协调器保证，这里只限制登录流程。
                if (!verifyingPlayback && result.closedByPage && !pageCloseRetry.tryUse()) {
                    cancelVerification("验证未通过，请重试")
                    return@QqVerificationDialog
                }
                val credential = try { auth.completeVerification(verification, result) }
                catch (error: Exception) {
                    state = state.copy(working = false, message = error.message ?: "验证失败，请重试")
                    return@QqVerificationDialog
                }
                if (!verifyingPlayback) {
                    loginVerification = null
                    if (qrVerification) {
                        qrVerification = false
                        // QQ验证后重新取码，旧qrsig不能混入新二维码，验证凭据只用于换票。
                        if (!qrController.restartAfterVerification(credential))
                            qrController.start(MusicSource.QQ, state.loginMethod, auth.session().deviceId, credential)
                    } else retry?.let { block -> retry = null; execute(block) }
                } else QqSessionRequestCoordinator.complete(result, verification)
            })
        }
    }
}
