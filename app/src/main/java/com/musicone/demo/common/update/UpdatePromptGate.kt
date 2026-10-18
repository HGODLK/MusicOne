package com.musicone.demo

internal const val UPDATE_REMINDER_INTERVAL_MS = 72L * 60 * 60 * 1_000
internal const val UPDATE_OPENING_WINDOW_MS = 5_000L

/** 提醒窗口只由应用重新打开建立，用户开始操作或应用离开前台后立即关闭。 */
internal class UpdatePromptGate {
    private var openedAt: Long? = null
    private var foreground = false

    fun open(nowElapsed: Long) {
        openedAt = nowElapsed
        foreground = false
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) close()
    }

    fun close() {
        openedAt = null
    }

    fun allows(nowElapsed: Long, nowWall: Long, lastShownAt: Long?): Boolean {
        val start = openedAt ?: return false
        val opening = nowElapsed >= start && nowElapsed - start < UPDATE_OPENING_WINDOW_MS
        val intervalPassed = lastShownAt == null ||
            (nowWall >= lastShownAt && nowWall - lastShownAt >= UPDATE_REMINDER_INTERVAL_MS)
        return foreground && opening && intervalPassed
    }
}
