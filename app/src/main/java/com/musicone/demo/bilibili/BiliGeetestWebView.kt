package com.musicone.demo

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 极验安全验证悬浮遮罩层。
 *
 * 遵循无成品 Material 对话框/Surface 约束，通过基础 Box 呈现并嵌入 WebView。
 */
@Composable
internal fun BiliGeetestOverlay(
    challenge: BiliGeetestChallenge,
    onSuccess: (BiliGeetestResult) -> Unit,
    onDismiss: () -> Unit,
    onFailure: (BiliGeetestFailure) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "安全人机验证",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭验证",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // 极验 WebView 内容区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    BiliGeetestWebView(
                        challenge = challenge,
                        onResult = onSuccess,
                        onClose = onDismiss,
                        onFailure = onFailure,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BiliGeetestWebView(
    challenge: BiliGeetestChallenge,
    onResult: (BiliGeetestResult) -> Unit,
    onClose: () -> Unit,
    onFailure: (BiliGeetestFailure) -> Unit,
) {
    val webViewRef = remember(challenge) { arrayOfNulls<WebView>(1) }
    val active = remember(challenge) { java.util.concurrent.atomic.AtomicBoolean(true) }
    var ready by remember(challenge) { mutableStateOf(false) }
    val latestResult = rememberUpdatedState(onResult)
    val latestClose = rememberUpdatedState(onClose)
    val latestFailure = rememberUpdatedState(onFailure)

    // 原生兜底覆盖网页脚本完全未执行的情况，就绪后允许用户正常完成验证。
    LaunchedEffect(challenge, ready) {
        if (!ready) {
            delay(20_000L)
            if (active.getAndSet(false)) latestFailure.value(BiliGeetestFailure.TIMEOUT)
        }
    }
    DisposableEffect(challenge) {
        BiliAuthDiagnostics.event("geetest_open")
        onDispose {
            active.set(false)
            webViewRef[0]?.apply {
                stopLoading()
                removeJavascriptInterface("AndroidGeetest")
                destroy()
            }
            webViewRef[0] = null
            BiliAuthDiagnostics.event("geetest_disposed")
        }
    }

    key(challenge) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewRef[0] = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    fun fail(failure: BiliGeetestFailure) {
                        if (active.getAndSet(false)) latestFailure.value(failure)
                    }
                    addJavascriptInterface(
                        BiliGeetestBridge(
                            onReady = { post { if (active.get()) {
                                ready = true
                                BiliAuthDiagnostics.event("geetest_ready")
                            } } },
                            onSuccess = { result -> post { if (active.getAndSet(false)) latestResult.value(result) } },
                            onClose = { post { if (active.getAndSet(false)) latestClose.value() } },
                            onFailure = { failure -> post { fail(failure) } },
                        ),
                        "AndroidGeetest",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                BiliAuthDiagnostics.event("geetest_web_error", error?.errorCode)
                                fail(BiliGeetestFailure.NETWORK)
                            }
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) {
                                BiliAuthDiagnostics.event("geetest_web_http", errorResponse?.statusCode)
                                fail(BiliGeetestFailure.NETWORK)
                            }
                        }
                    }
                    loadDataWithBaseURL("https://passport.bilibili.com", buildBiliGeetestHtml(challenge.gt, challenge.challenge),
                        "text/html", "UTF-8", null)
                }
            },
        )
    }
}
