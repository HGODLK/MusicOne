package com.musicone.source.qq

import com.musicone.source.qq.internal.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QqSmsLoginContent(state: QqLoginUiState, actions: QqLoginActions) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.countryCode,
                onValueChange = actions.onCountryCodeChange,
                modifier = Modifier.width(100.dp),
                label = { Text("区号") },
                prefix = { Text("+") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = state.phone,
                onValueChange = actions.onPhoneChange,
                modifier = Modifier.weight(1f),
                label = { Text("手机号码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.captcha,
                onValueChange = actions.onCaptchaChange,
                modifier = Modifier.weight(1f),
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            OutlinedButton(
                onClick = actions.onSendCaptcha,
                enabled = state.canSendCaptcha,
            ) {
                Text(if (state.resendSeconds > 0) "${state.resendSeconds} 秒" else "获取验证码")
            }
        }
        state.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        Button(
            onClick = actions.onLogin,
            enabled = state.canLogin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.working) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 10.dp).size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text("登录")
        }
    }
}

@Composable
internal fun QqQrLoginContent(state: QqLoginUiState, onRefresh: () -> Unit, reduceMotion: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedContent(state, contentKey = { it.qrStatus }, transitionSpec = {
            (fadeIn(qqLoginMotion(reduceMotion, 240)) togetherWith fadeOut(qqLoginMotion(reduceMotion, 180)))
                .using(SizeTransform(sizeAnimationSpec = { _, _ -> qqLoginMotion(reduceMotion, 300) }))
        }, label = "QQ二维码状态衔接") { displayed ->
            when (displayed.qrStatus) {
                PlatformQrLoginStatus.LOADING -> Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                PlatformQrLoginStatus.WAITING_SCAN, PlatformQrLoginStatus.WAITING_CONFIRM -> {
                    when {
                        displayed.qrImageBase64.isNotBlank() -> QrBase64Image(displayed.qrImageBase64)
                        displayed.qrUrl.isNotBlank() -> QrCodeImage(displayed.qrUrl)
                    }
                }
                PlatformQrLoginStatus.IDLE, PlatformQrLoginStatus.EXPIRED,
                PlatformQrLoginStatus.SUCCESS, PlatformQrLoginStatus.FAILED -> Unit
            }
        }
        Text(
            state.qrMessage ?: "打开 QQ音乐 App 扫描二维码",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        if (state.qrStatus == PlatformQrLoginStatus.EXPIRED || state.qrStatus == PlatformQrLoginStatus.FAILED) {
            Button(onClick = onRefresh) { Text("重新生成") }
        }
    }
}
