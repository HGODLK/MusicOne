package com.musicone.source.loginui

import com.musicone.source.sdk.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** 登录方式切换动画用的纯展示状态，只保存界面文案和图片，不放登录凭证。 */
internal data class SourceLoginContentState(
    val method: String, val busy: Boolean, val qrUrl: String?, val signedIn: Boolean,
    val message: String?, val error: String?, val country: String, val phone: String,
    val code: String, val resendSeconds: Int, val canLogin: Boolean,
)

/** 通用登录卡片和切换动效（样式和 QQ 登录一致），具体的登录逻辑由各音源包自己管。 */
@Composable
internal fun SourceLoginScreen(title: String, accent: Color, state: SourceLoginContentState,
    reduceMotion: Boolean, onSelectMethod: (String) -> Unit,
    accountContent: @Composable () -> Unit,
    content: @Composable (SourceLoginContentState, Boolean) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = if (dark) .14f else .09f), MaterialTheme.colorScheme.background)))
            .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    accountContent()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("qr" to "扫码登录", "sms" to "短信登录").forEach { (id, label) ->
                            if (state.method == id) Button(onClick = { onSelectMethod(id) }, enabled = !state.busy,
                                modifier = Modifier.weight(1f)) { Text(label) }
                            else OutlinedButton(onClick = { onSelectMethod(id) }, enabled = !state.busy,
                                modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                    // 按登录方式做 AnimatedContent 的 key，切换时让旧页面用原来的状态播完退出动画
                    AnimatedContent(state, contentKey = { it.method }, transitionSpec = {
                        ((fadeIn(loginMotion(reduceMotion, 320)) + slideInHorizontally(loginMotion(reduceMotion, 360)) { it / 16 }) togetherWith
                            (fadeOut(loginMotion(reduceMotion, 220)) + slideOutHorizontally(loginMotion(reduceMotion, 280)) { -it / 16 }))
                            .using(SizeTransform(sizeAnimationSpec = { _, _ -> loginMotion(reduceMotion, 300) }))
                    }, label = "音源登录方式衔接") { displayed -> content(displayed, displayed.method == state.method) }
                }
            }
            Spacer(Modifier.height(110.dp))
        }
    }
}

private fun <T> loginMotion(reduceMotion: Boolean, duration: Int): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else tween(duration, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))

@Composable
internal fun SourceLoginAccount(account: SourceAccountState, busy: Boolean, onRefresh: () -> Unit, onLogout: () -> Unit) {
    val summary = account.account ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary.displayName, style = MaterialTheme.typography.titleMedium)
            Text("账号：${summary.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (account.status == SourceSessionStatus.EXPIRED) Text("会话已失效，请重新登录", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新账号") }
                TextButton(onClick = onLogout, enabled = !busy) { Text("退出登录") }
            }
        }
    }
}

@Composable
internal fun SourceLoginFeedback(message: String?, error: String?) {
    if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    else if (!message.isNullOrBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
