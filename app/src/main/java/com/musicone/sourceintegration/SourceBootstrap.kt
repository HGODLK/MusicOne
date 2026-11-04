package com.musicone.sourceintegration

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.musicone.source.sdk.*

@Composable
internal fun SourceBootstrap(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val host = remember { SourceApplicationHost.get(context) }
    LaunchedEffect(host) { host.start() }
    SourceServicesHost(host, content)
}

@Composable
private fun SourceServicesHost(host: SourceApplicationHost, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val destination by host.destination.collectAsState()
    val request by host.pendingUi.collectAsState()
    var pickerCall by remember { mutableStateOf<SourceUiCall?>(null) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerCall?.result?.complete(SourceUiResult(uri == null, if (uri == null) emptyMap() else mapOf("uri" to uri.toString())))
        pickerCall = null
    }
    LaunchedEffect(request) {
        request?.let { call ->
            when (call.request.kind) {
                "document", "lyrics", "artwork" -> {
                    pickerCall = call
                    document.launch(arrayOf(call.request.arguments["mimeType"] ?: if (call.request.kind == "artwork") "image/*" else "*/*"))
                }
                else -> call.result.completeExceptionally(UnsupportedOperationException("宿主不支持此界面请求：${call.request.kind}"))
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        SourceSettingsOverlay(host, destination)
    }
}
