package com.musicone.source.qq.internal

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 登录被判失效后的自动复核节奏。后台久了醒来时，第一批请求常撞上网络未就绪或票刚过期，
 * 被拒一次不代表票真的作废：先 20 秒后复核，再按 2、5、10、30 分钟退避，之后每小时一次；
 * 网络恢复时提前复核（与上次至少间隔 30 秒）。复核成功即由会话保存清除失效状态。
 */
internal class QqExpiryRecovery(private val nowMs: () -> Long = System::currentTimeMillis) {
    private var failures = 0
    private var nextAtMs = Long.MAX_VALUE
    private var lastAttemptMs = Long.MIN_VALUE / 2

    @Synchronized fun expired() {
        failures = 0
        nextAtMs = nowMs() + BACKOFF_MS[0]
    }

    @Synchronized fun due(): Boolean = nowMs() >= nextAtMs

    @Synchronized fun attempted() { lastAttemptMs = nowMs() }

    @Synchronized fun failed() {
        failures++
        nextAtMs = nowMs() + BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.lastIndex)]
    }

    /** 网络恢复：离上次复核够久就立刻再试一次。 */
    @Synchronized fun networkAvailable() {
        if (nextAtMs != Long.MAX_VALUE) nextAtMs = minOf(nextAtMs, maxOf(nowMs(), lastAttemptMs + NETWORK_GAP_MS))
    }

    @Synchronized fun recovered() {
        failures = 0
        nextAtMs = Long.MAX_VALUE
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(20_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 30 * 60_000L, 60 * 60_000L)
        const val NETWORK_GAP_MS = 30_000L
    }
}

/** 失效原因的可读描述：异常说明加业务码，不含票据和账号。 */
internal fun Throwable.qqExpiryReason(): String {
    val code = (this as? PlatformApiException)?.apiCode
    // 去掉通用前缀，只留括号里的具体原因。
    val text = message?.takeIf(String::isNotBlank)?.let { raw ->
        raw.removePrefix("QQ 音乐登录状态已失效").trim().removeSurrounding("（", "）").ifBlank { raw }
    } ?: javaClass.simpleName
    // 301 是包内表示“票据被拒”的占位码，不是服务端业务码，不附加。
    return if (code == null || code == 301 || text.contains(code.toString())) text else "$text（业务码 $code）"
}

/** 最近一次失效原因：持久化保存，登录页展示，同时写 logcat 和会话日志，重新登录后清除。 */
internal class QqExpiryNote(context: Context) {
    private val preferences = context.getSharedPreferences("qq_session_diagnostics", Context.MODE_PRIVATE)

    fun record(reason: String) {
        val at = System.currentTimeMillis()
        preferences.edit().putString(KEY_REASON, reason).putLong(KEY_AT, at).apply()
        Log.w(TAG, "QQ 音乐登录失效：$reason")
        QqSessionLog.record("失效原因：$reason")
    }

    fun clear() { preferences.edit().remove(KEY_REASON).remove(KEY_AT).apply() }

    /** 形如“账号校验被拒……（11-23 22:31）”，没有记录时为 null。 */
    fun describe(): String? {
        val reason = preferences.getString(KEY_REASON, null) ?: return null
        val at = preferences.getLong(KEY_AT, 0L).takeIf { it > 0L }
            ?.let { "（${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it))}）" }.orEmpty()
        return reason + at
    }

    private companion object {
        const val TAG = "MusicOneQQ"
        const val KEY_REASON = "last_expiry_reason"
        const val KEY_AT = "last_expiry_at"
    }
}
