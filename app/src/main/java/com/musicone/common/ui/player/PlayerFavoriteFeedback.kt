package com.musicone

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View

/** 双短振由一次波形提交，避免文本选择触感被厂商忽略或菜单切换取消第二次反馈。 */
@Suppress("DEPRECATION")
internal fun playerFavoriteAdditionFeedback(view: View) {
    if (!view.isHapticFeedbackEnabled || Settings.System.getInt(view.context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
    val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (!vibrator.hasVibrator()) return
    val timing = longArrayOf(0L, 18L, 70L, 18L)
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(timing, -1), attributes)
    } else vibrator.vibrate(timing, -1, attributes)
}
