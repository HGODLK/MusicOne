package com.musicone.source.netease

import androidx.compose.runtime.*
import com.musicone.source.sdk.*
import kotlinx.coroutines.*

internal class NeteaseAuthenticationPage(private val owner: () -> NeteasePackageState,
    private val authentication: NeteaseSourceAuthentication) : SourceAuthenticationPage {
    private var login by mutableStateOf(NeteaseLoginState())
    private var challenge by mutableStateOf<SourceAuthChallenge?>(null)
    private var qrJob: Job? = null
    private var resendJob: Job? = null
    private var requestJob: Job? = null
    private var revision = 0L
    fun cancel() {
        revision++
        qrJob?.cancel(); requestJob?.cancel(); resendJob?.cancel()
        challenge = null
        authentication.cancel()
        login = login.resetSms()
    }
    fun back(): Boolean {
        if (challenge?.kind == "web") { cancel(); login = login.copy(qrStatus = NeteaseQrLoginStatus.IDLE); return true }
        cancel(); return false
    }
    private fun beginQr() {
        cancel(); login = login.copy(qrStatus = NeteaseQrLoginStatus.LOADING, qrMessage = null)
        qrJob = owner().action {
            try {
                val created = authentication.begin("qr", emptyMap()); challenge = created
                if (created.kind == "web") return@action
                login = login.copy(qrUrl = created.displayUrl.orEmpty(), qrStatus = NeteaseQrLoginStatus.WAITING_SCAN, qrMessage = created.message)
                while (currentCoroutineContext().isActive) {
                    delay(1_500)
                    val result = authentication.continueAuthentication(requireNotNull(challenge), emptyMap())
                    if (result.challenge != null) challenge = result.challenge
                    when (result.progress) {
                        SourceAuthProgress.SUCCEEDED -> { complete(); return@action }
                        SourceAuthProgress.EXPIRED -> { login = login.copy(qrStatus = NeteaseQrLoginStatus.EXPIRED, qrMessage = result.message); return@action }
                        SourceAuthProgress.VERIFICATION_REQUIRED -> return@action
                        else -> login = login.copy(qrStatus = if (result.progress == SourceAuthProgress.CONFIRMATION_REQUIRED)
                            NeteaseQrLoginStatus.WAITING_CONFIRM else NeteaseQrLoginStatus.WAITING_SCAN, qrMessage = result.message)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { login = login.copy(qrStatus = NeteaseQrLoginStatus.FAILED, qrMessage = error.message) }
        }
    }
    private fun request(block: suspend () -> Unit) {
        if (login.working) return
        val expected = ++revision
        login = login.copy(working = true, message = null)
        requestJob = owner().action {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == expected) login = login.copy(message = error.message ?: "网易云请求失败，请重试")
            }
            finally { if (revision == expected) login = login.copy(working = false) }
        }
    }
    private fun sendCode() = request {
            val created = authentication.begin("sms", mapOf("phone" to login.phone, "countryCode" to login.countryCode))
            currentCoroutineContext().ensureActive()
            challenge = created
            login = login.copy(captchaSent = challenge?.kind == "sms", message = challenge?.message, resendSeconds = 60)
            resendJob?.cancel(); resendJob = owner().scope.launch { while (login.resendSeconds > 0) { delay(1000); login = login.copy(resendSeconds = (login.resendSeconds - 1).coerceAtLeast(0)) } }
    }
    private fun submit(input: Map<String, String>) = request {
            val result = authentication.continueAuthentication(requireNotNull(challenge) { "请先获取验证码" }, input)
            currentCoroutineContext().ensureActive()
            if (result.challenge != null) challenge = result.challenge
            if (result.progress == SourceAuthProgress.SUCCEEDED) complete() else login = login.copy(message = result.message)
    }
    private fun complete() {
        challenge = null; login = login.resetSms().copy(qrStatus = NeteaseQrLoginStatus.SUCCESS)
        owner().message = "登录成功"; if (!owner().back()) owner().primary("my"); owner().refreshLibrary(); owner().refreshHome()
        owner().environment.host.exitSource()
    }
    @Composable override fun Content() {
        DisposableEffect(this) { onDispose { cancel() } }
        if (challenge?.kind == "web") {
            NeteaseOfficialLoginContent(login.working, login.message, onCancel = { back() }, onComplete = { submit(mapOf("cookie" to it.cookie)) })
        } else NeteaseLoginScreen(login, NeteaseLoginActions(
            onBack = { cancel(); if (!owner().back()) owner().environment.host.exitSource() }, onStartQrLogin = ::beginQr,
            onStopQrLogin = { qrJob?.cancel(); if (challenge?.kind == "qr") authentication.cancel() },
            onSelectLoginMethod = { method ->
                if (method != login.loginMethod) {
                    cancel(); login = login.copy(loginMethod = method, qrStatus = NeteaseQrLoginStatus.IDLE)
                }
            },
            onCountryCodeChange = {
                val updated = login.changeCountry(it)
                if (updated != login) { cancel(); login = updated }
            }, onPhoneChange = {
                val updated = login.changePhone(it)
                if (updated != login) { cancel(); login = updated }
            },
            onCaptchaChange = { login = login.copy(captcha = it.filter(Char::isDigit).take(10), message = null) },
            onSendCaptcha = { sendCode() }, onLogin = { submit(mapOf("code" to login.captcha)) }))
    }
}
