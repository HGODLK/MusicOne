package com.musicone.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import androidx.media3.common.PlaybackException
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 监听 USB 与设置变化，并在保留播放位置的前提下重新选择输出链路。 */
internal class UsbAudioServiceCoordinator(
    private val context: Context,
    private val player: ExoPlayer,
    private val sink: MusicOneUsbAudioSink,
    private val onOutputLost: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val routeSwitchMutex = Mutex()
    private var lossRecoveryPending = false
    private val outputListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (isUsbOutputFailure(error, PlaybackOptions.usbLosslessOutput)) outputLost()
        }
    }
    private val silence = UsbSilenceFeeder({ openUsbSilentAudioTrack(context.applicationContext) }) { failure ->
        android.util.Log.w("MusicUsbSilence", "静音占位轨暂不可用：${failure.message}")
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> UsbAudioPermissionCoordinator.handleAttachIntent(context, intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (PlaybackOptions.usbLosslessOutput && device?.let(::isUsbAudioDevice) == true) outputLost()
                    else UsbAudioOutputStatus.deviceChanged()
                }
            }
        }
    }

    fun start() {
        registerReceiver()
        player.addListener(outputListener)
        scope.launch {
            combine(PlaybackOptions.usbLosslessOutputChanges, UsbAudioConnectionState.changes) { enabled, connected ->
                enabled && connected
            }.distinctUntilChanged().collect(silence::setActive)
        }
        scope.launch {
            PlaybackOptions.usbLosslessOutputChanges.drop(1).collect { enabled ->
                routeSwitchMutex.withLock {
                    sink.setEnabled(enabled)?.let { reprepare(it, restoringSystemOutput = !enabled) }
                }
            }
        }
        scope.launch {
            UsbAudioOutputStatus.deviceChanges.collect {
                if (!PlaybackOptions.usbLosslessOutput) return@collect
                routeSwitchMutex.withLock {
                    reprepare(sink.onDeviceChanged(), restoringSystemOutput = false)
                }
            }
        }
    }

    fun release() {
        scope.coroutineContext[Job]?.cancel()
        player.removeListener(outputListener)
        silence.release()
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun outputLost() {
        // 先同步撤销播放意图，阻止晚到的错误或曲末事件切歌。
        player.pause()
        onOutputLost()
        silence.setActive(false)
        if (lossRecoveryPending) return
        lossRecoveryPending = true
        val reset = sink.onDeviceChanged()
        scope.launch {
            try {
                routeSwitchMutex.withLock { reprepare(reset, restoringSystemOutput = true) }
            } finally {
                lossRecoveryPending = false
            }
        }
    }

    private suspend fun reprepare(routeReset: Deferred<Unit>, restoringSystemOutput: Boolean) {
        try {
            reprepareUsbRoute(player,
                awaitSystemRoute = { if (restoringSystemOutput) delay(SYSTEM_ROUTE_RECOVERY_MS) },
                awaitReset = {
                    check(withTimeoutOrNull(ROUTE_RESET_TIMEOUT_MS) { routeReset.await(); true } == true) {
                        "USB 输出重置超时"
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            player.pause()
            onOutputLost()
            android.util.Log.w("MusicOneUsbAudio", "USB 输出重建失败，保留当前歌曲并暂停", failure)
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val ROUTE_RESET_TIMEOUT_MS = 5_000L
        const val SYSTEM_ROUTE_RECOVERY_MS = 180L
    }
}
