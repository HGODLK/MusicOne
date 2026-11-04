package des.c5inco.torph.compose

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** True when the system asks for animations to be skipped (animator duration scale is 0). */
public fun isReducedMotion(context: Context): Boolean {
    val animatorsOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) { 1f }
    return animatorsOff || scale == 0f
}

/** Reads the reduced-motion setting once per context; call [TextMorph] with `respectReducedMotion = false` to ignore it. */
@Composable
public fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { isReducedMotion(context) }
}
