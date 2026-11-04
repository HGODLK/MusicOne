package com.musicone.source.bilibili

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.musicone.source.sdk.*
import com.musicone.source.loginui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class BiliSourceAuthenticationPage(private val state: () -> BiliPackageState,
    private val authentication: BiliSourceAuthentication) : SourceAuthenticationPage {
    private var backAction: (() -> Boolean)? = null
    fun onBack(): Boolean = backAction?.invoke() == true

    @Composable override fun Content() {
        val owner = state()
        val scope = rememberCoroutineScope()
        var account by remember(owner) { mutableStateOf(authentication.currentAccount()) }
        var method by remember { mutableStateOf("qr") }
        var country by remember { mutableStateOf("86") }
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var challenge by remember { mutableStateOf<SourceAuthChallenge?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var resendSeconds by remember { mutableIntStateOf(0) }
        fun cancelPending() { authentication.cancel(); challenge = null; code = ""; resendSeconds = 0 }
        fun update(result: SourceAuthResult) {
            message = result.message
            if (result.progress == SourceAuthProgress.SUCCEEDED) {
                account = authentication.currentAccount(); challenge = null; code = ""; resendSeconds = 0
            } else challenge = result.challenge
        }
        fun launchAction(block: suspend () -> Unit) {
            scope.launch {
                busy = true; error = null
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.asUserMessage() }
                finally { busy = false }
            }
        }
        fun startQr() {
            launchAction {
                cancelPending()
                message = null
                challenge = authentication.begin("qr", emptyMap())
                message = challenge?.message
            }
        }
        // 沿用原版扫码页进入即加载的行为，切回扫码时创建新的挑战。
        LaunchedEffect(method) {
            if (method == "qr" && account.status != SourceSessionStatus.AUTHENTICATED) startQr()
        }
        SideEffect { backAction = {
            if (challenge?.kind == "verification") { cancelPending(); true } else false
        } }
        DisposableEffect(owner) {
            val task = owner.environment.host.registerTask { scope.cancel() }
            onDispose { task.close(); authentication.cancel(); backAction = null }
        }
        val current = challenge
        LaunchedEffect(current?.id) {
            if (current?.kind == "qr") {
                try {
                    while (true) {
                        delay(2000)
                        val result = authentication.continueAuthentication(current, emptyMap())
                        update(result)
                        if (result.progress in setOf(SourceAuthProgress.SUCCEEDED, SourceAuthProgress.EXPIRED)) break
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.asUserMessage(); challenge = null }
            } else if (current?.kind == "sms") {
                for (remaining in 60 downTo 0) { resendSeconds = remaining; if (remaining > 0) delay(1000) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            SourceLoginScreen(
                title = "哔哩哔哩账号", accent = androidx.compose.ui.graphics.Color(BILI_PAGE_COLOR),
                state = SourceLoginContentState(method, busy, challenge?.takeIf { it.kind == "qr" }?.displayUrl,
                    account.status == SourceSessionStatus.AUTHENTICATED, message, error, country, phone, code,
                    resendSeconds, challenge?.kind == "sms" && code.isNotBlank()),
                reduceMotion = owner.environment.host.reduceMotion,
                onSelectMethod = { selected ->
                    if (selected != method) {
                        cancelPending(); method = selected; error = null; message = null
                    }
                },
                accountContent = {
                    SourceLoginAccount(account, busy,
                        onRefresh = { launchAction { account = authentication.refreshAccount(); message = "账号信息已刷新" } },
                        onLogout = { launchAction {
                            cancelPending(); authentication.logout(); account = authentication.currentAccount()
                            message = "已退出登录"
                        } })
                },
            ) { displayed, interactive ->
                if (displayed.method == "qr") {
                    val url = displayed.qrUrl
                    SourceLoginQrPanel("哔哩哔哩", displayed.busy, !url.isNullOrBlank(),
                        displayed.signedIn, displayed.message, displayed.error, ::startQr, interactive) {
                        if (!url.isNullOrBlank()) {
                            val image = remember(url) { qrImage(url) }
                            Image(image.asImageBitmap(), "哔哩哔哩登录二维码", Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SourceLoginSmsForm(displayed.country, displayed.phone, displayed.code, displayed.busy, displayed.resendSeconds,
                            canLogin = displayed.canLogin, interactive = interactive,
                            onCountryChange = { country = it; cancelPending(); message = null; error = null },
                            onPhoneChange = { phone = it; cancelPending(); message = null; error = null },
                            onCodeChange = { code = it },
                            onSend = { launchAction {
                                challenge = authentication.begin("sms", mapOf("countryCode" to country, "phone" to phone))
                                message = challenge?.message
                            } },
                            onLogin = {
                                val active = challenge
                                if (active?.kind == "sms") launchAction {
                                    update(authentication.continueAuthentication(active, mapOf("code" to code)))
                                }
                            })
                        SourceLoginFeedback(displayed.message, displayed.error)
                    }
                }
            }
            challenge?.takeIf { it.kind == "verification" }?.let { active ->
                authentication.verification(active)?.let { verification ->
                    BiliGeetestOverlay(verification, onSuccess = { proof -> launchAction {
                        update(authentication.continueAuthentication(active, mapOf("geetest_challenge" to proof.challenge,
                            "geetest_validate" to proof.validate, "geetest_seccode" to proof.seccode)))
                    } }, onDismiss = { cancelPending(); message = "已取消安全验证" },
                        onFailure = { failure -> cancelPending(); error = failure.message })
                }
            }
        }
    }
    private fun qrImage(url: String): Bitmap {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
