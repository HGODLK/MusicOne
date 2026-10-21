package com.musicone.demo

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun HyperOsIslandProbeSettings() {
    if (Build.VERSION.SDK_INT < 26) return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = {
            busy = true
            scope.launch {
                try { result = HyperOsIslandProbe.send(context) } finally { busy = false }
            }
        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (busy) "正在查询并发送…" else "发送独立超级岛测试通知")
        }
        if (result.isNotBlank()) {
            Text(result, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { result = HyperOsIslandProbe.clear(context) }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("清除测试通知") }
        }
    }
}
