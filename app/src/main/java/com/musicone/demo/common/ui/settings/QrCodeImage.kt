package com.musicone.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
internal fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { createQrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "平台登录二维码",
        modifier = modifier.size(236.dp).background(Color.White).padding(12.dp),
        filterQuality = FilterQuality.None,
    )
}

@Composable
internal fun QrBase64Image(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        val bytes = Base64.decode(content.substringAfter("base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "平台登录二维码",
        modifier = modifier.size(236.dp).background(Color.White).padding(12.dp),
        filterQuality = FilterQuality.None,
    )
}

private fun createQrBitmap(content: String, side: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        side,
        side,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(side * side) { index ->
        if (matrix[index % side, index / side]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}
