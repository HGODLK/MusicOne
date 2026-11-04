package com.musicone.sourceintegration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** SAF 只提供输入流；保留真实文件后缀交由运行时校验类型、签名和兼容性。 */
internal fun SourcePackageController.importDocument(context: Context, uri: Uri, allowDowngrade: Boolean = true) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: error("无法读取音源文件名")
    val extension = name.substringAfterLast('.', "").lowercase()
    require(extension == "mscm" || extension == "mscp") { "请选择 .mscm 主音源或 .mscp 补充音源文件" }
    val staging = File(context.cacheDir, "source-import-${UUID.randomUUID()}.$extension")
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开音源文件" }.use { input ->
            staging.outputStream().use { output -> input.copyTo(output) }
        }
        importPackage(staging, allowDowngrade)
    } finally { staging.delete() }
}
