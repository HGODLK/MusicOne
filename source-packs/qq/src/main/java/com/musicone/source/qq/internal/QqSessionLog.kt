package com.musicone.source.qq.internal

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QQ 会话事件日志：记录续票、校验、业务码和登录状态变化，用来排查偶发掉登录。
 * 写在应用外部私有目录 `Android/data/<包名>/files/diagnostics/qq-session.log`，电脑连 USB 可直接拷出。
 * 只记事件、来源、业务码和耗时，不记 Cookie、票据、账号、设备标识。超过上限后保留后一半。
 */
internal object QqSessionLog {
    private const val MAX_BYTES = 512 * 1024L
    @Volatile private var file: File? = null
    private val lock = Any()

    fun attach(context: Context) {
        if (file != null) return
        val directory = runCatching { context.getExternalFilesDir("diagnostics") }.getOrNull()
            ?: File(context.filesDir, "diagnostics")
        file = File(directory, "qq-session.log")
    }

    fun record(event: String) {
        val target = file ?: return
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} [${Thread.currentThread().name}] $event\n"
        synchronized(lock) {
            runCatching {
                target.parentFile?.mkdirs()
                if (target.length() > MAX_BYTES) trim(target)
                target.appendText(line)
            }
        }
    }

    private fun trim(target: File) {
        val text = target.readText()
        val keep = text.substring(text.length / 2).substringAfter('\n')
        target.writeText("…（较早记录已截断）\n$keep")
    }
}

/** 续票、校验失败的简短描述，只带异常类型和业务码。 */
internal fun Throwable.qqLogSummary(): String {
    val code = (this as? PlatformApiException)?.apiCode?.let { "，业务码=$it" }.orEmpty()
    return "${javaClass.simpleName}$code"
}
