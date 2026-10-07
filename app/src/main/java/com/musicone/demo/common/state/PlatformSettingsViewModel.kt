package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlatformSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private val auth = PlatformAuthRepository()
    private val qqPersistentSession = QqPersistentSession()
    private val saved = preferences.read()
    private val validationJobs = mutableMapOf<MusicSource, Job>()
    private var lastQqValidationMs = 0L
    private var pendingCaptchaCredential = ""
    private var pendingQrChallenge: PlatformQrChallenge? = null
    private var pendingSecurityChallenge: PlatformSecurityChallenge? = null
    private var pendingSecurityAction: SecurityRetryAction? = null
    private var qrLoginJob: Job? = null
    private val _state = MutableStateFlow(saved.initialUiState())
    val state: StateFlow<PlatformSettingsUiState> = _state.asStateFlow()

    init {
        val session = saved.session(saved.selectedSource)
        if (session.credential.isNotBlank()) validateSavedSession(saved.selectedSource)
        viewModelScope.launch {
            while (true) {
                delay(5 * 60 * 1_000L)
                refreshQqSessionIfDue()
            }
        }
    }

    fun refreshQqSessionIfDue() {
        if (_state.value.selectedSource != MusicSource.QQ) return
        val session = preferences.readSession(MusicSource.QQ)
        if (session.credential.isBlank()) return
        if (QqPersistentSession.shouldRefresh(session.credential) ||
            System.currentTimeMillis() - lastQqValidationMs >= 6 * 60 * 60 * 1_000L) {
            validateSavedSession(MusicSource.QQ)
        }
    }

    fun selectSource(source: MusicSource) {
        if (!auth.supports(source)) return
        stopQrLogin()
        clearSecurityVerification()
        preferences.saveSource(source)
        val session = preferences.readSession(source)
        _state.update {
            it.copy(
                selectedSource = source,
                sourceSelected = true,
                loginSource = source,
                account = session.account,
                sessionStatus = if (session.credential.isBlank()) SessionStatus.SIGNED_OUT else SessionStatus.CHECKING,
                message = null,
            )
        }
        if (session.credential.isNotBlank()) validateSavedSession(source)
    }

    fun startLogin(source: MusicSource = _state.value.selectedSource) {
        stopQrLogin()
        pendingCaptchaCredential = ""
        clearSecurityVerification()
        _state.update {
            it.copy(
                loginSource = source,
                loginMethod = if (source == MusicSource.QQ) PlatformLoginMethod.SMS else PlatformLoginMethod.QR_CODE,
                captcha = "",
                captchaSent = false,
                resendSeconds = 0,
                qrUrl = "",
                qrImageBase64 = "",
                qrStatus = PlatformQrLoginStatus.IDLE,
                qrMessage = null,
                securityVerification = null,
                message = null,
            )
        }
    }

    fun setPhone(value: String) {
        val phone = value.filter(Char::isDigit).take(20)
        if (_state.value.phone == phone) return
        pendingCaptchaCredential = ""
        clearSecurityVerification()
        _state.update { it.copy(phone = phone, captcha = "", captchaSent = false, resendSeconds = 0, message = null) }
    }

    fun setCountryCode(value: String) {
        val countryCode = value.filter(Char::isDigit).take(4)
        if (_state.value.countryCode == countryCode) return
        pendingCaptchaCredential = ""
        clearSecurityVerification()
        _state.update { it.copy(countryCode = countryCode, captcha = "", captchaSent = false, resendSeconds = 0, message = null) }
    }

    fun setCaptcha(value: String) {
        _state.update { it.copy(captcha = value.filter(Char::isDigit).take(10), message = null) }
    }

    fun selectLoginMethod(method: PlatformLoginMethod) {
        if (_state.value.loginSource == MusicSource.QQ && method == PlatformLoginMethod.QR_CODE) return
        if (_state.value.loginMethod == method) return
        if (method == PlatformLoginMethod.SMS) qrLoginJob?.cancel()
        _state.update { it.copy(loginMethod = method, message = null) }
        if (method == PlatformLoginMethod.QR_CODE) startQrLogin()
    }

    fun startQrLogin() {
        if (_state.value.loginSource == MusicSource.QQ) {
            _state.update { it.copy(loginMethod = PlatformLoginMethod.SMS, message = "QQ 音乐仅支持短信验证码登录") }
            return
        }
        if (qrLoginJob?.isActive == true) return
        qrLoginJob = viewModelScope.launch {
            val source = _state.value.loginSource
            _state.update {
                it.copy(
                    qrStatus = PlatformQrLoginStatus.LOADING,
                    qrUrl = "",
                    qrImageBase64 = "",
                    qrMessage = null,
                    message = null,
                )
            }
            val challenge = try {
                withContext(Dispatchers.IO) { auth.createQrCode(source) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (showSecurityVerification(error, SecurityRetryAction.START_QR)) return@launch
                _state.update { it.copy(qrStatus = PlatformQrLoginStatus.FAILED, qrMessage = error.asUserMessage()) }
                return@launch
            }
            pendingQrChallenge = challenge
            _state.update {
                it.copy(
                    qrUrl = challenge.url,
                    qrImageBase64 = challenge.imageBase64,
                    qrStatus = PlatformQrLoginStatus.WAITING_SCAN,
                    qrMessage = "请使用${source.scanAppName()}扫码",
                )
            }
            pollQrLogin()
        }
    }

    fun stopQrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
    }

    fun sendCaptcha() {
        val snapshot = _state.value
        if (snapshot.working || snapshot.resendSeconds > 0) return
        if (snapshot.phone.length < 6) {
            _state.update { it.copy(message = "请输入正确的手机号码") }
            return
        }
        val session = preferences.readSession(snapshot.loginSource)
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    auth.sendCaptcha(
                        snapshot.loginSource,
                        snapshot.phone,
                        snapshot.countryCode,
                        session,
                        pendingCaptchaCredential,
                    )
                }
            }.onSuccess { result ->
                pendingCaptchaCredential = result.credentialSeed
                _state.update {
                    it.copy(working = false, captchaSent = true, resendSeconds = 60, message = result.message)
                }
                countDownResend()
            }.onFailure { error ->
                if (!showSecurityVerification(error, SecurityRetryAction.SEND_CAPTCHA)) {
                    _state.update { it.copy(working = false, message = error.asUserMessage()) }
                }
            }
        }
    }

    fun login() {
        val snapshot = _state.value
        if (snapshot.working) return
        if (snapshot.phone.length < 6 || snapshot.captcha.length < 4 || !snapshot.captchaSent) {
            _state.update { it.copy(message = "请先获取并填写短信验证码") }
            return
        }
        val session = preferences.readSession(snapshot.loginSource)
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    auth.loginByCaptcha(
                        source = snapshot.loginSource,
                        phone = snapshot.phone,
                        countryCode = snapshot.countryCode,
                        captcha = snapshot.captcha,
                        session = session,
                        credentialSeed = pendingCaptchaCredential,
                    )
                }
            }.onSuccess { result ->
                pendingCaptchaCredential = ""
                onLoginSuccess(result.credential, result.account)
            }.onFailure { error ->
                if (!showSecurityVerification(error, SecurityRetryAction.LOGIN)) {
                    _state.update { it.copy(working = false, message = error.asUserMessage()) }
                }
            }
        }
    }

    fun completeSecurityVerification(result: PlatformSecurityVerificationResult) {
        val challenge = pendingSecurityChallenge ?: return
        val action = pendingSecurityAction ?: return
        if (_state.value.working) return
        viewModelScope.launch {
            // 验证结果交回后先释放窗口，后台续接不再保留透明触摸层。
            _state.update { it.copy(working = true, message = null, securityVerification = null) }
            runCatching {
                withContext(Dispatchers.IO) { auth.completeSecurityVerification(challenge, result) }
            }.onSuccess { credential ->
                if (pendingSecurityChallenge !== challenge) return@onSuccess
                when (action) {
                    SecurityRetryAction.SEND_CAPTCHA, SecurityRetryAction.LOGIN ->
                        pendingCaptchaCredential = mergePlatformCredentials(pendingCaptchaCredential, credential)
                    SecurityRetryAction.CHECK_QR -> pendingQrChallenge = pendingQrChallenge?.copy(
                        credentialSeed = mergePlatformCredentials(pendingQrChallenge?.credentialSeed.orEmpty(), credential),
                    )
                    SecurityRetryAction.START_QR -> Unit
                }
                clearSecurityVerification()
                _state.update { it.copy(working = false, securityVerification = null) }
                when (action) {
                    SecurityRetryAction.SEND_CAPTCHA -> sendCaptcha()
                    SecurityRetryAction.LOGIN -> login()
                    SecurityRetryAction.START_QR -> startQrLogin()
                    SecurityRetryAction.CHECK_QR -> resumeQrPolling()
                }
            }.onFailure { error ->
                if (pendingSecurityChallenge !== challenge) return@onFailure
                _state.update { it.copy(working = false, message = error.asUserMessage()) }
            }
        }
    }

    fun cancelSecurityVerification() {
        clearSecurityVerification()
        _state.update {
            it.copy(
                working = false,
                securityVerification = null,
                qrStatus = if (it.loginMethod == PlatformLoginMethod.QR_CODE) PlatformQrLoginStatus.FAILED else it.qrStatus,
                qrMessage = if (it.loginMethod == PlatformLoginMethod.QR_CODE) "已取消安全验证" else it.qrMessage,
                message = if (it.loginMethod == PlatformLoginMethod.SMS) "已取消安全验证" else it.message,
            )
        }
    }

    fun logout() {
        stopQrLogin()
        pendingCaptchaCredential = ""
        clearSecurityVerification()
        val source = _state.value.selectedSource
        preferences.clearSession(source)
        _state.update {
            it.copy(
                account = null,
                sessionStatus = SessionStatus.SIGNED_OUT,
                sessionRevision = it.sessionRevision + 1,
                captcha = "",
                captchaSent = false,
                message = "已退出${source.label}",
            )
        }
    }

    private suspend fun pollQrLogin() {
        while (true) {
            delay(2_000)
            val challenge = pendingQrChallenge ?: return
            val result = try {
                withContext(Dispatchers.IO) { auth.checkQrCode(challenge) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (showSecurityVerification(error, SecurityRetryAction.CHECK_QR)) return
                _state.update { it.copy(qrStatus = PlatformQrLoginStatus.FAILED, qrMessage = error.asUserMessage()) }
                return
            }
            pendingQrChallenge = challenge.copy(credentialSeed = result.credential)
            _state.update { it.copy(qrStatus = result.status, qrMessage = result.message) }
            when (result.status) {
                PlatformQrLoginStatus.EXPIRED, PlatformQrLoginStatus.FAILED -> return
                PlatformQrLoginStatus.SUCCESS -> {
                    val account = result.account ?: return
                    pendingQrChallenge = null
                    onLoginSuccess(result.credential, account)
                    return
                }
                else -> Unit
            }
        }
    }

    private fun onLoginSuccess(credential: String, account: MusicAccount) {
        preferences.saveSession(credential, account)
        if (account.source == MusicSource.QQ) lastQqValidationMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                selectedSource = account.source,
                account = account,
                sessionStatus = SessionStatus.CONNECTED,
                sessionRevision = it.sessionRevision + 1,
                working = false,
                captcha = "",
                captchaSent = false,
                qrUrl = "",
                qrImageBase64 = "",
                qrStatus = PlatformQrLoginStatus.SUCCESS,
                qrMessage = "登录成功",
                securityVerification = null,
                message = "登录成功",
            )
        }
    }

    private fun validateSavedSession(source: MusicSource) {
        val session = preferences.readSession(source)
        if (session.credential.isBlank() || validationJobs[source]?.isActive == true) return
        validationJobs[source] = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (source == MusicSource.QQ) qqPersistentSession.validate(session)
                    else QqValidatedSession(session.credential, auth.account(source, session.credential))
                }
            }
                .onSuccess { validated ->
                    if (preferences.credential(source) != session.credential) return@onSuccess
                    preferences.saveSession(validated.credential, validated.account)
                    if (source == MusicSource.QQ) lastQqValidationMs = System.currentTimeMillis()
                    if (_state.value.selectedSource == source) {
                        _state.update {
                            it.copy(
                                account = validated.account,
                                sessionStatus = SessionStatus.CONNECTED,
                                sessionRevision = it.sessionRevision + 1,
                                message = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (preferences.credential(source) != session.credential) return@onFailure
                    if (error.invalidSession()) {
                        if (source != MusicSource.QQ) preferences.clearSession(source)
                        if (_state.value.selectedSource == source) {
                            _state.update {
                                it.copy(
                                    account = null,
                                    sessionStatus = SessionStatus.SIGNED_OUT,
                                    sessionRevision = it.sessionRevision + 1,
                                    message = if (source == MusicSource.QQ) "QQ 音乐登录续期失败，请稍后重试或重新登录" else null,
                                )
                            }
                        }
                    } else if (_state.value.selectedSource == source) {
                        _state.update {
                            it.copy(
                                sessionStatus = if (session.account == null) SessionStatus.SIGNED_OUT else SessionStatus.CONNECTED,
                                message = if (session.account == null) null else "暂时无法验证登录状态",
                            )
                        }
                    }
                }
        }
    }

    private suspend fun countDownResend() {
        while (_state.value.resendSeconds > 0) {
            delay(1_000)
            _state.update { it.copy(resendSeconds = (it.resendSeconds - 1).coerceAtLeast(0)) }
        }
    }

    private fun showSecurityVerification(error: Throwable, action: SecurityRetryAction): Boolean {
        val challenge = (error as? PlatformSecurityVerificationRequired)?.challenge ?: return false
        pendingSecurityChallenge = challenge
        pendingSecurityAction = action
        _state.update {
            it.copy(
                working = false,
                securityVerification = challenge.toUiState(),
                qrStatus = if (action == SecurityRetryAction.CHECK_QR) PlatformQrLoginStatus.WAITING_CONFIRM else it.qrStatus,
                qrMessage = if (action == SecurityRetryAction.CHECK_QR) "需要完成安全验证" else it.qrMessage,
                message = null,
            )
        }
        return true
    }

    private fun clearSecurityVerification() {
        pendingSecurityChallenge = null
        pendingSecurityAction = null
    }

    private fun resumeQrPolling() {
        qrLoginJob?.cancel()
        qrLoginJob = viewModelScope.launch { pollQrLogin() }
    }
}

private enum class SecurityRetryAction {
    SEND_CAPTCHA,
    LOGIN,
    START_QR,
    CHECK_QR,
}

private fun SavedPlatformSettings.initialUiState(): PlatformSettingsUiState {
    val selected = session(selectedSource)
    return PlatformSettingsUiState(
        selectedSource = selectedSource,
        sourceSelected = sourceSelected || selected.credential.isNotBlank(),
        loginSource = selectedSource,
        account = selected.account,
        sessionStatus = if (selected.credential.isBlank()) SessionStatus.SIGNED_OUT else SessionStatus.CHECKING,
    )
}

private fun MusicSource.scanAppName(): String = when (this) {
    MusicSource.NETEASE -> "网易云音乐"
    MusicSource.QQ -> "QQ"
    MusicSource.KUGOU -> "酷狗音乐"
}

private fun Throwable.invalidSession(): Boolean =
    (this is NeteaseApiException && apiCode == 301) ||
        this is QqCredentialExpiredException ||
        (this is PlatformApiException && apiCode == 301)
