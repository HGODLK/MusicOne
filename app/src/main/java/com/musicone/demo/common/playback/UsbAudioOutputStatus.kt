package com.musicone.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UsbAudioOutputRoute {
    DISABLED,
    WAITING_FOR_DEVICE,
    PERMISSION_REQUIRED,
    READY,
    SYSTEM_BIT_PERFECT,
    DIRECT_UAC2,
    FALLBACK,
    UNSUPPORTED_ANDROID,
}

internal data class UsbAudioOutputSnapshot(
    val route: UsbAudioOutputRoute = UsbAudioOutputRoute.DISABLED,
    val deviceName: String? = null,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val detail: String? = null,
)

/** USB 输出状态由播放线程发布，设置页只负责展示。 */
internal object UsbAudioOutputStatus {
    private val mutable = MutableStateFlow(UsbAudioOutputSnapshot())
    val current = mutable.asStateFlow()
    private val mutableDeviceChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val deviceChanges = mutableDeviceChanges

    fun publish(snapshot: UsbAudioOutputSnapshot) {
        mutable.value = snapshot
    }

    fun deviceChanged() {
        mutableDeviceChanges.tryEmit(Unit)
    }
}
