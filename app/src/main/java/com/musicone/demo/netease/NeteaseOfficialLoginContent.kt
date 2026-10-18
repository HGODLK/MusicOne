package com.musicone.demo

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** 仅在服务端要求验证时使用；必须取得真实登录 Cookie，拦截提示页不能手动冒充成功。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NeteaseOfficialLoginContent(working: Boolean, error: String?, onCancel: () -> Unit,
    onComplete: (PlatformSecurityVerificationResult) -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf("") }
    val complete by rememberUpdatedState(onComplete)
    LaunchedEffect(view, prepared) {
        if (view == null || !prepared) return@LaunchedEffect
        while (true) {
            candidate = mergePlatformCredentials(*neteaseWebCookieUrls.map {
                CookieManager.getInstance().getCookie(it).orEmpty()
            }.toTypedArray())
            if (!submitted && neteaseHasLoginCookie(candidate)) {
                submitted = true
                complete(PlatformSecurityVerificationResult(cookie = candidate))
            }
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("网易云官方网页登录", modifier = Modifier.padding(vertical = 16.dp))
            TextButton(onClick = onCancel) { Text("返回") }
        }
        Text("短信登录需要额外验证，请在官方页面完成登录，成功后自动返回。", Modifier.padding(horizontal = 20.dp))
        error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        if (error != null) TextButton(enabled = !working && neteaseHasLoginCookie(candidate),
            onClick = { complete(PlatformSecurityVerificationResult(cookie = candidate)) }) { Text("重新检查登录结果") }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
            WebView(context).apply {
                view = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                webViewClient = WebViewClient()
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                manager.setAcceptThirdPartyCookies(this, true)
                // 仅清理网易云登录域，保留 QQ 等其他平台的 WebView 会话。
                val expired = neteaseWebCookieUrls.flatMap { url ->
                    manager.getCookie(url).orEmpty().cookieValues().keys.flatMap { name ->
                        listOf(url to "$name=; Max-Age=0; Path=/", url to "$name=; Max-Age=0; Path=/; Domain=.music.163.com")
                    }
                }
                fun start() { manager.flush(); prepared = true; loadUrl("https://music.163.com/#/login") }
                if (expired.isEmpty()) start() else {
                    var remaining = expired.size
                    expired.forEach { (url, cookie) -> manager.setCookie(url, cookie) {
                        if (--remaining == 0) post { if (view === this) start() }
                    } }
                }
            }
        })
    }
    DisposableEffect(Unit) { onDispose { prepared = false; view?.stopLoading(); view?.destroy(); view = null } }
}

private val neteaseWebCookieUrls = listOf("https://music.163.com/", "https://interface.music.163.com/")
