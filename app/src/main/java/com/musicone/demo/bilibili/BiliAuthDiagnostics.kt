package com.musicone.demo

import android.util.Log
import org.json.JSONObject

/** 认证日志仅记录固定阶段、状态码和异常类型，不记录凭据、号码或响应正文。 */
internal object BiliAuthDiagnostics {
    fun event(stage: String, code: Int? = null) {
        runCatching { Log.i("BiliAuth", "stage=$stage" + (code?.let { " code=$it" } ?: "")) }
    }

    fun request(stage: String, call: () -> PlatformHttpResponse): JSONObject {
        event("${stage}_start")
        try {
            val response = call()
            event("${stage}_http", response.status)
            return JSONObject(response.text).also { event("${stage}_result", it.optInt("code", -1)) }
        } catch (error: Exception) {
            (error as? PlatformApiException)?.apiCode?.let { event("${stage}_http_failed", it) }
            runCatching { Log.w("BiliAuth", "stage=${stage}_failed type=${error.javaClass.simpleName}") }
            throw error
        }
    }
}

internal enum class BiliSmsPhase { IDLE, SENDING, VERIFYING, SUBMITTING, SENT, LOGGING_IN }
