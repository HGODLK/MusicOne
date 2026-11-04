package com.musicone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 稳定态直接绘制；仅准备时录制旧帧，随后在旧帧下面预绘制同一个目标子树。 */
@Composable
internal fun PhonePlayerPreparedTurnHost(preparation: PhonePlayerTurnPreparation?, content: @Composable () -> Unit) {
    if (preparation == null) { content(); return }
    val layer = rememberGraphicsLayer()
    val isolationPaint = remember { Paint() }
    val owner = remember { Any() }
    var captureRequest by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    DisposableEffect(preparation, owner) {
        preparation.attach(owner) {
            val request = CompletableDeferred<Unit>()
            captureRequest = request
            try {
                request.await()
                val image = layer.toImageBitmap()
                currentCoroutineContext().ensureActive()
                preparation.image = image
            } finally { captureRequest = null }
        }
        onDispose { preparation.detach(owner); captureRequest?.cancel() }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawWithContent {
            val request = captureRequest
            if (request != null && !request.isCompleted) {
                if (preparation.sourceFrameDrawn()) {
                    layer.record {
                        this@drawWithContent.drawContent()
                        // 渐变中再次转向时，封存正在显示的混合画面，不能退回渐变起点。
                        preparation.image?.let { drawImage(it, alpha = preparation.sourceAlpha.value) }
                    }
                    drawLayer(layer)
                    request.complete(Unit)
                } else drawContent()
            } else {
                drawPhoneTurnPreparedFrame(preparation.image, preparation.sourceAlpha.value,
                    preparation.preparing && preparation.drawingTarget, isolationPaint) {
                    this@drawWithContent.drawContent()
                    preparation.targetDrawn()
                }
            }
        }) { content() }
        if (preparation.active) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        })
    }
}
