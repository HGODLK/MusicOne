package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun QqLoginMethodSelector(method: PlatformLoginMethod, onSelect: (PlatformLoginMethod) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QqLoginMethodButton("短信登录", method == PlatformLoginMethod.SMS, enabled,
            { onSelect(PlatformLoginMethod.SMS) }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QqLoginMethodButton("QQ 扫码", method == PlatformLoginMethod.QR_CODE, enabled,
                { onSelect(PlatformLoginMethod.QR_CODE) }, Modifier.weight(1f))
            QqLoginMethodButton("微信扫码", method == PlatformLoginMethod.WECHAT_QR_CODE, enabled,
                { onSelect(PlatformLoginMethod.WECHAT_QR_CODE) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QqLoginMethodButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (selected) Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
}
