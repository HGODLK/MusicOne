package com.musicone.demo

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@Composable
internal fun PlatformSecurityVerificationScreen(
    state: PlatformSecurityVerificationUiState,
    working: Boolean,
    errorMessage: String?,
    onCancel: () -> Unit,
    onComplete: (PlatformSecurityVerificationResult) -> Unit,
) {
    BackHandler(onBack = onCancel)
    if (state.source == MusicSource.QQ && state.kind == PlatformSecurityVerificationKind.WEB &&
        qqVkeyVerificationPage(state.url)
    ) {
        key(state.instanceId) {
            QqOfficialVerificationContent(state, working, errorMessage, onComplete)
        }
        return
    }
    if (state.source == MusicSource.QQ && state.kind in listOf(
            PlatformSecurityVerificationKind.WEB,
            PlatformSecurityVerificationKind.TENCENT_CAPTCHA,
        )
    ) {
        key(state.instanceId) {
            SecurityWebContent(state, working, onComplete, Modifier.fillMaxSize(), errorMessage, onCancel, true)
        }
        return
    }
    Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.title, style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("关闭") }
            }
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            when (state.kind) {
                PlatformSecurityVerificationKind.SMS -> SecuritySmsContent(working, onComplete)
                PlatformSecurityVerificationKind.WEB,
                PlatformSecurityVerificationKind.TENCENT_CAPTCHA -> SecurityWebContent(
                    state,
                    working,
                    onComplete,
                    Modifier.fillMaxWidth().weight(1f),
                    errorMessage,
                    onCancel,
                )
                PlatformSecurityVerificationKind.MANUAL -> SecurityManualContent(working, onComplete)
            }
        }
    }
}

@Composable
private fun SecurityManualContent(
    working: Boolean,
    onComplete: (PlatformSecurityVerificationResult) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "MusicOne 已暂停当前 QQ 会话的播放取票和批量缓存请求。完成官方确认后，点击下方按钮仅重试一次原请求。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { onComplete(PlatformSecurityVerificationResult()) },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp)
            Text("已在官方 QQ 音乐完成确认")
        }
    }
}

@Composable
private fun SecuritySmsContent(
    working: Boolean,
    onComplete: (PlatformSecurityVerificationResult) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(10) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("安全验证码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Button(
            onClick = { onComplete(PlatformSecurityVerificationResult(payload = code)) },
            enabled = !working && code.length >= 4,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp)
            Text("提交验证")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
@Composable
private fun SecurityWebContent(
    state: PlatformSecurityVerificationUiState,
    working: Boolean,
    onComplete: (PlatformSecurityVerificationResult) -> Unit,
    modifier: Modifier,
    errorMessage: String?,
    onCancel: () -> Unit,
    bare: Boolean = false,
) {
    val webViewHolder = remember(state) { arrayOfNulls<WebView>(1) }
    var currentUrl by remember(state) { mutableStateOf(state.url) }
    val touchPoints = remember(state) { mutableListOf<PlatformTouchPoint>() }
    var touchStartedAt by remember(state) { mutableStateOf(0L) }
    var submitted by remember(state) { mutableStateOf(false) }
    val latestComplete by rememberUpdatedState(onComplete)
    val latestCancel by rememberUpdatedState(onCancel)
    LaunchedEffect(working, errorMessage) { if (!working && errorMessage != null) submitted = false }

    fun finish(payload: String = "") {
        if (submitted) return
        val view = webViewHolder[0] ?: return
        submitted = true
        val cookieManager = CookieManager.getInstance()
        val cookie = mergePlatformCredentials(
            cookieManager.getCookie(state.url).orEmpty(),
            cookieManager.getCookie(currentUrl).orEmpty(),
        )
        latestComplete(
            PlatformSecurityVerificationResult(
                payload = payload,
                cookie = cookie,
                touchPoints = touchPoints.toList(),
                viewportWidth = view.width,
                viewportHeight = view.height,
            ),
        )
    }

    fun cancel() {
        if (submitted) return
        submitted = true
        latestCancel()
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply webViewSetup@{
                webViewHolder[0] = this
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                if (state.source == MusicSource.QQ) settings.userAgentString += " QQMUSIC/140200"
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@webViewSetup, true)
                    if (state.kind == PlatformSecurityVerificationKind.WEB && state.url.startsWith("http")) {
                        val targets = if (state.source == MusicSource.QQ) {
                            qqVerificationCookieTargets(state.url)
                        } else {
                            listOf(state.url)
                        }
                        targets.forEach { target ->
                            state.credentialSeed.cookieValues().forEach { (name, value) ->
                                setCookie(target, "$name=$value; Path=/; Secure")
                            }
                        }
                        flush()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        if (state.source == MusicSource.QQ && qqVerificationPage(window.url.orEmpty())) {
                            finish()
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme.orEmpty().lowercase()
                        if (state.source == MusicSource.QQ && qqVerificationPage(view.url.orEmpty()) &&
                            qqVerificationCloseCallback(request.url.toString())) {
                            finish()
                        }
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        currentUrl = url
                        if (state.source == MusicSource.QQ && qqVerificationPage(url)) view.evaluateJavascript(qqCaptchaProofListener, null)
                    }
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            if (touchStartedAt == 0L) touchStartedAt = event.eventTime
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_MOVE ||
                        event.actionMasked == MotionEvent.ACTION_UP
                    ) {
                        val elapsed = (event.eventTime - touchStartedAt).coerceAtLeast(0L)
                        val previous = touchPoints.lastOrNull()
                        if (touchPoints.size < MAX_TOUCH_POINTS &&
                            (previous == null || elapsed - previous.elapsedMs >= TOUCH_SAMPLE_INTERVAL_MS ||
                                event.actionMasked != MotionEvent.ACTION_MOVE)
                        ) {
                            touchPoints += PlatformTouchPoint(elapsed, event.x.toInt(), event.y.toInt())
                        }
                    }
                    false
                }
                if (state.kind == PlatformSecurityVerificationKind.TENCENT_CAPTCHA) {
                    addJavascriptInterface(TencentCaptchaBridge { payload -> post { finish(payload) } }, BRIDGE_NAME)
                    loadDataWithBaseURL(
                        TENCENT_CAPTCHA_BASE_URL,
                        tencentCaptchaHtml(state.appId),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                } else {
                    if (state.source == MusicSource.QQ) addJavascriptInterface(TencentCaptchaBridge(onClose = {
                        post {
                            if (qqVerificationPage(url.orEmpty())) {
                                finish()
                            }
                        }
                    }) { payload ->
                        post {
                            val proof = runCatching { JSONObject(payload) }.getOrNull()
                            if (qqVerificationPage(url.orEmpty()) && proof?.optInt("ret", -1) == 0 &&
                                proof.optString("ticket").isNotBlank() && proof.optString("randstr").isNotBlank()
                            ) {
                                finish(payload)
                            }
                        }
                    }, BRIDGE_NAME)
                    loadUrl(state.url)
                }
            }
        },
        modifier = modifier,
    )
    if (!bare && state.kind == PlatformSecurityVerificationKind.WEB) {
        Button(
            onClick = { finish() },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            if (working) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp)
            Text("已完成验证，继续")
        }
    } else if (!bare) {
        OutlinedButton(
            onClick = { webViewHolder[0]?.reload() },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Text("重新加载验证")
        }
    }
    DisposableEffect(state) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                removeJavascriptInterface(BRIDGE_NAME)
                destroy()
            }
            webViewHolder[0] = null
        }
    }
}

private class TencentCaptchaBridge(private val onClose: () -> Unit = {}, private val onComplete: (String) -> Unit) {
    @JavascriptInterface
    fun complete(payload: String) = onComplete(payload)
    @JavascriptInterface
    fun close() = onClose()
}

private fun tencentCaptchaHtml(appId: String): String {
    val quotedAppId = JSONObject.quote(appId)
    return """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
          <style>html,body{margin:0;width:100%;height:100%;background:transparent}</style>
          <script src="https://turing.captcha.qcloud.com/TCaptcha.js"></script>
        </head>
        <body>
          <script>
            window.onload = function () {
              var captcha = new TencentCaptcha($quotedAppId, function (result) {
                if (result && result.ret === 0) {
                  MusicOneVerification.complete(JSON.stringify({
                    ticket: result.ticket,
                    randstr: result.randstr,
                    txappid: $quotedAppId
                  }));
                }
              });
              captcha.show();
            };
          </script>
        </body>
        </html>
    """.trimIndent()
}

private const val BRIDGE_NAME = "MusicOneVerification"
private const val TENCENT_CAPTCHA_BASE_URL = "https://turing.captcha.qcloud.com/"
private const val MAX_TOUCH_POINTS = 256
private const val TOUCH_SAMPLE_INTERVAL_MS = 16L
