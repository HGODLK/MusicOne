package com.musicone.source.kugou

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
@Composable internal fun KugouSecurityVerificationScreen(state: PlatformSecurityVerificationUiState, working: Boolean,
    error: String?, onCancel: () -> Unit, onComplete: (PlatformSecurityVerificationResult) -> Unit) {
    BackHandler(onBack = onCancel)
    var code by remember(state) { mutableStateOf("") }
    val view = remember(state.instanceId) { arrayOfNulls<WebView>(1) }
    val points = remember(state.instanceId) { mutableListOf<PlatformTouchPoint>() }
    var start by remember(state.instanceId) { mutableLongStateOf(0) }
    var submitted by remember(state.instanceId) { mutableStateOf(false) }
    var currentUrl by remember(state.instanceId) { mutableStateOf(state.url) }
    val latestComplete by rememberUpdatedState(onComplete)
    LaunchedEffect(working, error) { if (!working && error != null) submitted = false }
    fun complete(payload: String) {
        if (submitted || working) return
        submitted = true
        val cookies = CookieManager.getInstance()
        latestComplete(PlatformSecurityVerificationResult(payload,
            mergePlatformCredentials(cookies.getCookie(state.url).orEmpty(), cookies.getCookie(currentUrl).orEmpty()),
            points.toList(), view[0]?.width ?: 0, view[0]?.height ?: 0))
    }
    DisposableEffect(state.instanceId) { onDispose { view[0]?.apply { stopLoading(); removeJavascriptInterface("MusicOneVerification"); destroy() }; view[0] = null } }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            Text(state.message)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.kind == PlatformSecurityVerificationKind.SMS) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, label = { Text("安全验证码") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Button({ complete(code) }, enabled = !working && code.length >= 4) { Text("提交验证") }
            } else AndroidView(factory = { context -> WebView(context).apply webViewSetup@{
                view[0] = this
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                CookieManager.getInstance().apply {
                    setAcceptCookie(true); setAcceptThirdPartyCookies(this@webViewSetup, true)
                    if (state.url.startsWith("http")) state.credentialSeed.cookieValues().forEach { (name, value) -> setCookie(state.url, "$name=$value; Path=/; Secure") }
                    flush()
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme !in setOf("http", "https")
                    override fun onPageFinished(view: WebView, url: String) { currentUrl = url }
                }
                setOnTouchListener { touched, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) { touched.parent?.requestDisallowInterceptTouchEvent(true); if (start == 0L) start = event.eventTime }
                    if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) touched.parent?.requestDisallowInterceptTouchEvent(false)
                    val elapsed = (event.eventTime - start).coerceAtLeast(0)
                    if (event.actionMasked in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP) && points.size < 256 &&
                        (points.lastOrNull()?.let { elapsed - it.elapsedMs >= 16 } != false || event.actionMasked != MotionEvent.ACTION_MOVE)) {
                        points += PlatformTouchPoint(elapsed, event.x.toInt(), event.y.toInt())
                    }; false
                }
                if (state.kind == PlatformSecurityVerificationKind.TENCENT_CAPTCHA) {
                    addJavascriptInterface(SecurityCaptchaBridge { payload -> post { complete(payload) } }, "MusicOneVerification")
                    loadDataWithBaseURL("https://turing.captcha.qcloud.com/", kugouTencentHtml(state.appId), "text/html", "UTF-8", null)
                } else loadUrl(state.url)
            } }, modifier = Modifier.fillMaxWidth().weight(1f))
            if (state.kind == PlatformSecurityVerificationKind.WEB) Button({ complete("") }, enabled = !working) { Text("已完成验证，继续") }
            if (state.kind == PlatformSecurityVerificationKind.TENCENT_CAPTCHA) OutlinedButton({ view[0]?.reload() }, enabled = !working) { Text("重新加载验证") }
            TextButton(onCancel) { Text("取消") }
        }
    }
}
internal class SecurityCaptchaBridge(private val result: (String) -> Unit) {
    @JavascriptInterface fun complete(payload: String) = result(payload)
}
private fun kugouTencentHtml(appId: String): String {
    val id = JSONObject.quote(appId)
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><script src="https://turing.captcha.qcloud.com/TCaptcha.js"></script></head><body><script>window.onload=function(){var captcha=new TencentCaptcha($id,function(r){if(r&&r.ret===0)MusicOneVerification.complete(JSON.stringify({ticket:r.ticket,randstr:r.randstr,txappid:$id}));});captcha.show();};</script></body></html>"""
}
