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
    private var pendingSecurityChallenge: PlatformSecurityChallenge? = null
    private var pendingSecurityAction: SecurityRetryAction? = null
    private val _state = MutableStateFlow(saved.initialUiState())
    val state: StateFlow<PlatformSettingsUiState> = _state.asStateFlow()

    private val qrLogin = PlatformQrLoginController(
        scope = viewModelScope,
        create = { source, method, deviceId -> withContext(Dispatchers.IO) { auth.createQrCode(source, method, deviceId) } },
        check = { challenge -> withContext(Dispatchers.IO) { auth.checkQrCode(challenge) } },
        onChanged = { status, message, challenge ->
            _state.update { it.copy(qrStatus = status, qrMessage = message,
                qrUrl = challenge?.url.orEmpty(), qrImageBase64 = challenge?.imageBase64.orEmpty()) }
        },
        onSuccess = ::onLoginSuccess,
        onSecurity = { error, checking ->
            // QQ/微信换票的授权码是一次性的，完成安全验证后重新生成二维码。
            showSecurityVerification(error, if (checking && _state.value.loginSource != MusicSource.QQ) {
                SecurityRetryAction.CHECK_QR
            } else SecurityRetryAction.START_QR)
        },
    )

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
        if (source == MusicSource.BILIBILI || (source.requiresAccount && !auth.supports(source))) return
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
                sessionStatus = if (!source.requiresAccount) SessionStatus.CONNECTED else if (session.credential.isBlank()) SessionStatus.SIGNED_OUT else SessionStatus.CHECKING,
                message = null,
            )
        }
        if (session.credential.isNotBlank()) validateSavedSession(source)
    }

    fun startLogin(source: MusicSource = _state.value.selectedSource) {
        if (!source.requiresAccount || source == MusicSource.BILIBILI) return
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
        if (method == PlatformLoginMethod.WECHAT_QR_CODE && _state.value.loginSource != MusicSource.QQ) return
        if (_state.value.loginMethod == method || _state.value.working) return
        stopQrLogin()
        clearSecurityVerification()
        _state.update { it.copy(loginMethod = method, message = null, securityVerification = null) }
        if (method != PlatformLoginMethod.SMS) startQrLogin()
    }

    fun startQrLogin() {
        val snapshot = _state.value
        if (snapshot.loginMethod == PlatformLoginMethod.SMS || snapshot.working) return
        qrLogin.start(snapshot.loginSource, snapshot.loginMethod, preferences.readSession(snapshot.loginSource).deviceId)
    }

    fun stopQrLogin() {
        qrLogin.stop()
        _state.update { it.copy(qrStatus = PlatformQrLoginStatus.IDLE, qrUrl = "", qrImageBase64 = "", qrMessage = null) }
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
            runCatchingCancellable {
                // 发出请求前保留设备信息，首次网络失败后重试仍使用同一设备。
                pendingCaptchaCredential = auth.prepareCaptchaCredential(snapshot.loginSource, session, pendingCaptchaCredential)
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
                (error as? KugouSmsRequestException)?.let { pendingCaptchaCredential = it.credentialSeed }
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
            runCatchingCancellable {
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
                (error as? KugouSmsRequestException)?.let { pendingCaptchaCredential = it.credentialSeed }
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
        if (challenge.source == MusicSource.NETEASE) {
            if (!neteaseHasLoginCookie(result.cookie)) {
                _state.update { it.copy(message = "官方页面尚未完成登录") }
                return
            }
            viewModelScope.launch {
                _state.update { it.copy(working = true, message = null) }
                runCatchingCancellable { withContext(Dispatchers.IO) { auth.account(MusicSource.NETEASE, result.cookie) } }
                    .onSuccess { account ->
                        if (pendingSecurityChallenge !== challenge) return@onSuccess
                        clearSecurityVerification()
                        pendingCaptchaCredential = ""
                        onLoginSuccess(result.cookie, account)
                    }.onFailure { error ->
                        if (pendingSecurityChallenge === challenge) _state.update { it.copy(working = false, message = error.asUserMessage()) }
                    }
            }
            return
        }

        viewModelScope.launch {
            // 验证结果交回后先释放窗口，后台续接不再保留透明触摸层。
            _state.update { it.copy(working = true, message = null, securityVerification = null) }
            runCatchingCancellable {
                withContext(Dispatchers.IO) { auth.completeSecurityVerification(challenge, result) }
            }.onSuccess { credential ->
                if (pendingSecurityChallenge !== challenge) return@onSuccess
                clearSecurityVerification()
                if (challenge.isKugouAccountRecovery()) {
                    pendingCaptchaCredential = ""
                    _state.update(PlatformSettingsUiState::afterKugouAccountRecovery)
                    return@onSuccess
                }
                when (action) {
                    SecurityRetryAction.SEND_CAPTCHA, SecurityRetryAction.LOGIN ->
                        pendingCaptchaCredential = mergePlatformCredentials(pendingCaptchaCredential, credential)
                    SecurityRetryAction.CHECK_QR -> qrLogin.mergeVerificationCredential(credential)
                    SecurityRetryAction.START_QR -> Unit
                }
                _state.update { it.copy(working = false, securityVerification = null) }
                when (action) {
                    SecurityRetryAction.SEND_CAPTCHA -> sendCaptcha()
                    SecurityRetryAction.LOGIN -> login()
                    SecurityRetryAction.START_QR -> {
                        if (challenge.source != MusicSource.QQ || !qrLogin.restartAfterVerification(credential)) startQrLogin()
                    }
                    SecurityRetryAction.CHECK_QR -> qrLogin.resume()
                }
            }.onFailure { error ->
                if (pendingSecurityChallenge !== challenge) return@onFailure
                _state.update { it.copy(working = false, message = error.asUserMessage()) }
            }
        }
    }

    fun cancelSecurityVerification() {
        val recovery = pendingSecurityChallenge?.isKugouAccountRecovery() == true
        clearSecurityVerification()
        if (recovery) {
            pendingCaptchaCredential = ""
            _state.update { it.afterKugouAccountRecovery().copy(message = "已取消账号恢复；恢复后请重新获取短信验证码登录") }
            return
        }
        _state.update {
            it.copy(
                working = false,
                securityVerification = null,
                qrStatus = if (it.loginMethod != PlatformLoginMethod.SMS) PlatformQrLoginStatus.FAILED else it.qrStatus,
                qrMessage = if (it.loginMethod != PlatformLoginMethod.SMS) "已取消安全验证" else it.qrMessage,
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

    private fun onLoginSuccess(credential: String, account: MusicAccount) {
        qrLogin.stop()
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
            runCatchingCancellable {
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
        sessionStatus = if (!selectedSource.requiresAccount) SessionStatus.CONNECTED else if (selected.credential.isBlank()) SessionStatus.SIGNED_OUT else SessionStatus.CHECKING,
    )
}

private fun Throwable.invalidSession(): Boolean =
    (this is NeteaseApiException && apiCode == 301) ||
        this is QqCredentialExpiredException ||
        (this is PlatformApiException && apiCode == 301)
