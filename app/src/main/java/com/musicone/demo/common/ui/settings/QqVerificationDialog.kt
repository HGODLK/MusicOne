package com.musicone.demo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** 原生窗口不参与父 AnimatedVisibility 的退出等待，验证状态清空即释放触摸与焦点。 */
@Composable
internal fun QqVerificationDialog(verification: PlatformSecurityVerificationUiState, working: Boolean,
    message: String?, onCancel: () -> Unit, onComplete: (PlatformSecurityVerificationResult) -> Unit) {
    key(verification.instanceId) {
        Dialog(onDismissRequest = onCancel,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                PlatformSecurityVerificationScreen(verification, working, message,
                    onCancel, onComplete)
                // 网页没有发出关闭消息时，仍提供可退出的原生入口，不外套第二张卡片。
                IconButton(onCancel,
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp)) {
                    Icon(Icons.Default.Close, "关闭验证码")
                }
            }
        }
    }
}

/** QQ 播放验证依赖官方客户端的登录 Cookie 与设备桥接，第三方 WebView 无法替代。 */
@Composable
internal fun QqOfficialVerificationContent(
    verification: PlatformSecurityVerificationUiState,
    working: Boolean,
    message: String?,
    onComplete: (PlatformSecurityVerificationResult) -> Unit,
) {
    val context = LocalContext.current
    var launchError by rememberSaveable(verification.instanceId) { mutableStateOf<String?>(null) }

    fun openOfficialApp() {
        launchError = openQqOfficialVerification(context, verification.url)
    }

    LaunchedEffect(verification.instanceId) { openOfficialApp() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
    ) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("请在 QQ 音乐完成验证", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "QQ 播放验证需要官方客户端的登录与设备环境。完成滑块后返回此处，再确认继续。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    (launchError ?: message)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { onComplete(PlatformSecurityVerificationResult()) },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (working) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 10.dp).size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("已完成验证，继续")
                    }
                    OutlinedButton(
                        onClick = ::openOfficialApp,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("重新打开 QQ 音乐")
                    }
                }
            }
        }
    }
}

private fun openQqOfficialVerification(context: Context, url: String): String? {
    val deepLink = qqOfficialVerificationDeepLink(url)
        ?: return "验证地址无效，请关闭后重新发起播放"
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                setPackage(QQ_MUSIC_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        null
    } catch (_: ActivityNotFoundException) {
        "未检测到官方 QQ 音乐，请安装并登录后重试"
    } catch (_: SecurityException) {
        "无法打开官方 QQ 音乐，请手动打开并完成验证"
    }
}

private const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
