package com.musicone.demo

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

internal data class NeteaseBackgroundCropRequest(
    val uri: Uri,
    val target: NeteaseBackgroundTarget,
)

private data class NeteaseCropPreview(
    val loading: Boolean = true,
    val bitmap: Bitmap? = null,
)

/**
 * 保存前裁出与屏幕一致的竖屏区域。用户可以拖动和双指缩放，最终只保存裁剪结果，
 * 后续页面无需每次重新解码整张相机原图。
 */
@Composable
internal fun NeteaseBackgroundCropDialog(
    request: NeteaseBackgroundCropRequest,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap, NeteaseCropSelection) -> Unit,
) {
    val context = LocalContext.current
    val preview by produceState(NeteaseCropPreview(), request.uri) {
        value = NeteaseCropPreview(
            loading = false,
            bitmap = NeteaseProfileBackgroundStore.loadPreview(context.applicationContext, request.uri),
        )
    }
    val bitmap = preview.bitmap
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .94f)).padding(horizontal = 16.dp),
        ) {
            val availableWidth = (maxWidth - 16.dp).coerceAtLeast(1.dp)
            val availableHeight = (maxHeight - 190.dp).coerceAtLeast(1.dp)
            val aspectRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val cropWidth = minOf(availableWidth, availableHeight * aspectRatio)
            val cropHeight = cropWidth / aspectRatio
            val cropSelection = remember(request.uri, aspectRatio) {
                mutableStateOf(NeteaseCropSelection(aspectRatio = aspectRatio))
            }
            Column(
                Modifier.fillMaxSize().padding(top = 54.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("裁剪背景", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "拖动选择区域，双指缩放图片",
                        modifier = Modifier.padding(top = 6.dp),
                        color = Color.White.copy(alpha = .68f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(Modifier.size(cropWidth, cropHeight), contentAlignment = Alignment.Center) {
                    when {
                        bitmap != null -> NeteaseCropViewport(
                            bitmap = bitmap,
                            selection = cropSelection,
                            modifier = Modifier.fillMaxSize(),
                        )
                        preview.loading -> CircularProgressIndicator(color = Color.White)
                        else -> Text("这张图片无法读取", color = Color.White.copy(alpha = .82f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color.White) }
                    TextButton(
                        enabled = bitmap != null,
                        onClick = {
                            bitmap?.let { source ->
                                onConfirm(
                                    source,
                                    cropSelection.value,
                                )
                            }
                        },
                    ) { Text("使用此区域") }
                }
            }
        }
    }
}

@Composable
private fun NeteaseCropViewport(
    bitmap: Bitmap,
    selection: MutableState<NeteaseCropSelection>,
    modifier: Modifier,
) {
    val current = selection.value
    Canvas(
        modifier
            .background(Color.Black)
            .border(2.dp, Color.White.copy(alpha = .92f), RoundedCornerShape(2.dp))
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val before = selection.value
                    val zoom = (before.zoom * gestureZoom).coerceIn(1f, 6f)
                    val rect = neteaseCropRect(
                        bitmap.width,
                        bitmap.height,
                        before.copy(zoom = zoom),
                    )
                    val centerX = (before.centerX - pan.x / size.width.coerceAtLeast(1) * rect.width / bitmap.width)
                        .coerceIn(rect.width / 2f / bitmap.width, 1f - rect.width / 2f / bitmap.width)
                    val centerY = (before.centerY - pan.y / size.height.coerceAtLeast(1) * rect.height / bitmap.height)
                        .coerceIn(rect.height / 2f / bitmap.height, 1f - rect.height / 2f / bitmap.height)
                    selection.value = before.copy(centerX = centerX, centerY = centerY, zoom = zoom)
                }
            },
    ) {
        val rect = neteaseCropRect(bitmap.width, bitmap.height, current)
        drawImage(
            image = bitmap.asImageBitmap(),
            srcOffset = IntOffset(rect.x, rect.y),
            srcSize = IntSize(rect.width, rect.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        val thirdX = size.width / 3f
        val thirdY = size.height / 3f
        val guide = Color.White.copy(alpha = .24f)
        drawLine(guide, Offset(thirdX, 0f), Offset(thirdX, size.height), 1f)
        drawLine(guide, Offset(thirdX * 2f, 0f), Offset(thirdX * 2f, size.height), 1f)
        drawLine(guide, Offset(0f, thirdY), Offset(size.width, thirdY), 1f)
        drawLine(guide, Offset(0f, thirdY * 2f), Offset(size.width, thirdY * 2f), 1f)
    }
}
