package com.musicone

import android.view.KeyEvent

/** 仅在 USB 独占输出工作时接管音量键，普通播放走系统默认逻辑。 */
internal object UsbExclusiveVolumeKeys {
    @Volatile var activeGain: SystemMediaVolumeGain? = null

    fun dispatch(event: KeyEvent): Boolean {
        val gain = activeGain ?: return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) gain.adjust(direction)
        return true
    }
}
