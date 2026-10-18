package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 账号登录与状态切换区块。
 */
@Composable
internal fun BiliAuthSection(
    state: BiliLibraryUiState,
    viewModel: BiliLibraryViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val account = state.account
        if (account == null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QqSegmentButton(
                    label = "扫码登录",
                    selected = state.loginMethod == PlatformLoginMethod.QR_CODE,
                    onClick = { viewModel.switchLoginMethod(PlatformLoginMethod.QR_CODE) },
                    modifier = Modifier.weight(1f),
                )
                QqSegmentButton(
                    label = "短信登录",
                    selected = state.loginMethod == PlatformLoginMethod.SMS,
                    onClick = { viewModel.switchLoginMethod(PlatformLoginMethod.SMS) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.loginMethod == PlatformLoginMethod.QR_CODE) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val qr = state.qrCode
                    if (qr != null && state.qrStatus != PlatformQrLoginStatus.EXPIRED) {
                        QrCodeImage(
                            content = qr.url,
                            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    } else Box(Modifier.size(200.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        if (state.qrStatus == PlatformQrLoginStatus.LOADING) CircularProgressIndicator()
                        else Icon(Icons.Default.QrCode2, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = state.qrMessage ?: when (state.qrStatus) {
                            PlatformQrLoginStatus.WAITING_SCAN -> "请使用哔哩哔哩手机客户端扫码"
                            PlatformQrLoginStatus.WAITING_CONFIRM -> "已扫码，请在手机上确认登录"
                            PlatformQrLoginStatus.LOADING -> "正在生成二维码..."
                            PlatformQrLoginStatus.EXPIRED -> "二维码已过期，请重新获取"
                            else -> "点击下方按钮生成登录二维码"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LibraryActionButton(
                        title = if (state.qrCode == null || state.qrStatus == PlatformQrLoginStatus.EXPIRED) "生成二维码" else "刷新二维码",
                        enabled = !state.busy,
                        onClick = viewModel::startQrLogin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryFormField(
                        label = "手机号",
                        value = state.phone,
                        onChange = viewModel::updatePhone,
                    )
                    LibraryActionButton(
                        title = when (state.smsPhase) {
                            BiliSmsPhase.SENDING -> "正在获取验证码…"
                            BiliSmsPhase.VERIFYING -> "请完成安全验证"
                            BiliSmsPhase.SUBMITTING -> "正在提交安全验证…"
                            else -> if (state.resendSeconds > 0) "已发送 (${state.resendSeconds}s)" else "获取验证码"
                        },
                        enabled = state.resendSeconds == 0 && state.phone.isNotBlank() && !state.busy && state.geetestChallenge == null,
                        onClick = { viewModel.sendSms() },
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                    LibraryFormField(
                        label = "短信验证码",
                        value = state.smsCode,
                        onChange = viewModel::updateSmsCode,
                    )
                    LibraryActionButton(
                        title = if (state.smsPhase == BiliSmsPhase.LOGGING_IN) "登录中..." else "确认登录",
                        enabled = state.smsCode.isNotBlank() && state.captchaKey.isNotBlank() && !state.busy && state.geetestChallenge == null,
                        onClick = viewModel::loginBySms,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account.name.ifBlank { "B站用户" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "UID: ${account.mid}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LibraryActionButton(
                title = "退出当前B站账号",
                onClick = viewModel::logout,
                primary = false,
            )
            Text("仅退出补充音源账号，已导入的歌曲会保留。", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BiliTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
