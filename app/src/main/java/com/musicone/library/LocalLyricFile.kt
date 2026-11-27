package com.musicone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** 在 IO 线程查 SAF 文件的真实扩展名，查不到时退回从 URI 路径里截取。 */
internal fun readLocalLyricExtension(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.substringAfterLast('.', "").lowercase() in localLyricExtensions }
    return localLyricExtension(name ?: uri.lastPathSegment.orEmpty())
}
