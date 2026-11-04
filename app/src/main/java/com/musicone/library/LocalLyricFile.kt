package com.musicone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** 在 IO 线程读取真实文件名；文档提供者查询失效时沿用 URI 中的扩展名。 */
internal fun readLocalLyricExtension(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.substringAfterLast('.', "").lowercase() in localLyricExtensions }
    return localLyricExtension(name ?: uri.lastPathSegment.orEmpty())
}
