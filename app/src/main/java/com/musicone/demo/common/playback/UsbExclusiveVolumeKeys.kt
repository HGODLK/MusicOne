package com.musicone.demo

import android.view.KeyEvent

/** 只接管正在运行的 UAC2 输出，普通播放仍交给系统处理音量键。 */
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
