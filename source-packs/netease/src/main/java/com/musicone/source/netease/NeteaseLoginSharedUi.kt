package com.musicone.source.netease

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
internal fun NeteaseLoginPageTitle(label: String, description: String) {
    Column {
        Text("登录$label", fontSize = 18.sp)
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
internal fun NeteaseLoginMethodButtons(
    method: NeteaseLoginMethod,
    onSelect: (NeteaseLoginMethod) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (method == NeteaseLoginMethod.QR_CODE) {
            Button(onClick = { onSelect(NeteaseLoginMethod.QR_CODE) }, modifier = Modifier.weight(1f)) {
                Text("扫码登录")
            }
        } else {
            OutlinedButton(onClick = { onSelect(NeteaseLoginMethod.QR_CODE) }, modifier = Modifier.weight(1f)) {
                Text("扫码登录")
            }
        }
        if (method == NeteaseLoginMethod.SMS) {
            Button(onClick = { onSelect(NeteaseLoginMethod.SMS) }, modifier = Modifier.weight(1f)) {
                Text("短信登录")
            }
        } else {
            OutlinedButton(onClick = { onSelect(NeteaseLoginMethod.SMS) }, modifier = Modifier.weight(1f)) {
                Text("短信登录")
            }
        }
    }
}

@Composable
internal fun NeteaseSmsLoginContent(state: NeteaseLoginState, actions: NeteaseLoginActions) {
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
                enabled = !state.working && state.resendSeconds == 0,
            ) {
                Text(if (state.resendSeconds > 0) "${state.resendSeconds} 秒" else "获取验证码")
            }
        }
        state.message?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        Button(
            onClick = actions.onLogin,
            enabled = !state.working && state.phone.length >= 6 && state.captcha.length >= 4 && state.captchaSent,
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
internal fun NeteaseQrLoginContent(state: NeteaseLoginState, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (state.qrStatus) {
            NeteaseQrLoginStatus.LOADING -> Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            NeteaseQrLoginStatus.WAITING_SCAN, NeteaseQrLoginStatus.WAITING_CONFIRM -> {
                when {
                    state.qrImageBase64.isNotBlank() -> QrBase64Image(state.qrImageBase64)
                    state.qrUrl.isNotBlank() -> QrCodeImage(state.qrUrl)
                }
            }
            NeteaseQrLoginStatus.IDLE, NeteaseQrLoginStatus.EXPIRED,
            NeteaseQrLoginStatus.SUCCESS, NeteaseQrLoginStatus.FAILED -> Unit
        }
        Text(
            state.qrMessage ?: "打开${state.loginSource.label} App 扫描二维码",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        if (state.qrStatus == NeteaseQrLoginStatus.EXPIRED || state.qrStatus == NeteaseQrLoginStatus.FAILED) {
            Button(onClick = onRefresh) { Text("重新生成") }
        }
    }
}
