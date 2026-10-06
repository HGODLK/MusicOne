package com.musicone.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 监听 USB 与设置变化，并在保留播放位置的前提下重新选择输出链路。 */
internal class UsbAudioServiceCoordinator(
    private val context: Context,
    private val player: ExoPlayer,
    private val sink: MusicOneUsbAudioSink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val routeSwitchMutex = Mutex()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> UsbAudioPermissionCoordinator.handleAttachIntent(context, intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> UsbAudioOutputStatus.deviceChanged()
            }
        }
    }

    fun start() {
        registerReceiver()
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
        runCatching { context.unregisterReceiver(receiver) }
    }

    private suspend fun reprepare(routeReset: Deferred<Unit>, restoringSystemOutput: Boolean) {
        if (player.currentMediaItem == null) return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        if (player.playbackState != Player.STATE_IDLE) {
            player.stop()
            withTimeoutOrNull(ROUTE_RESET_TIMEOUT_MS) { routeReset.await() }
            if (restoringSystemOutput) delay(SYSTEM_ROUTE_RECOVERY_MS)
        }
        player.seekTo(position)
        player.prepare()
        player.playWhenReady = playWhenReady
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
