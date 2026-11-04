package com.musicone.source.loginui

import com.musicone.source.sdk.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 短信表单采用 QQ 既有字段分组，小屏自动换行，避免验证码按钮挤占输入区。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceLoginSmsForm(country: String, phone: String, code: String, busy: Boolean, resendSeconds: Int,
    canLogin: Boolean, onCountryChange: (String) -> Unit, onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit, onSend: () -> Unit, onLogin: () -> Unit, interactive: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("使用手机号接收验证码并登录", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(country, onCountryChange, Modifier.width(88.dp), enabled = !busy && interactive,
                label = { Text("区号") }, prefix = { Text("+") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(phone, onPhoneChange, Modifier.weight(1f), enabled = !busy && interactive,
                label = { Text("手机号码") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, onCodeChange, Modifier.widthIn(min = 120.dp).weight(1f), enabled = !busy && interactive,
                label = { Text("验证码") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            OutlinedButton(onClick = onSend, enabled = interactive && !busy && resendSeconds == 0 && phone.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (resendSeconds > 0) "$resendSeconds 秒后重发" else "获取验证码")
            }
        }
        Button(onClick = onLogin, enabled = interactive && !busy && canLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp)
            Text(if (busy) "正在处理" else "登录")
        }
    }
}

/** 二维码始终占据固定区域，加载、失效与重试不使下方操作跳动。 */
@Composable
internal fun SourceLoginQrPanel(platform: String, busy: Boolean, hasQr: Boolean, signedIn: Boolean, message: String?, error: String?,
    onRefresh: () -> Unit, interactive: Boolean = true, qrContent: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (signedIn && !hasQr && !busy) "当前账号已登录，可重新扫码切换账号" else "打开${platform} App，扫描下方二维码", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.widthIn(max = 236.dp).fillMaxWidth().aspectRatio(1f).background(Color.White).padding(10.dp),
            contentAlignment = Alignment.Center) {
            if (hasQr) qrContent()
            else if (busy) CircularProgressIndicator()
            else Text(when {
                !error.isNullOrBlank() -> "二维码暂不可用"
                signedIn -> "已登录"
                else -> "点击下方按钮获取二维码"
            }, color = Color.DarkGray)
        }
        SourceLoginFeedback(message, error)
        OutlinedButton(onClick = onRefresh, enabled = interactive && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(when {
                busy -> "正在生成二维码"
                hasQr -> "刷新二维码"
                signedIn -> "扫码切换账号"
                else -> "重新生成二维码"
            })
        }
    }
}
