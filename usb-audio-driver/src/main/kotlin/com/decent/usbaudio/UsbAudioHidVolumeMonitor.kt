package com.decent.usbaudio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * 监听 USB DAC 附带的 HID Consumer Control 接口，捕获小尾巴机身物理音量键（音量加／减）。
 * 仅认领经 Report Descriptor 确认包含音量键的 HID 接口，不误占厂商调参通道；停止时立即释放接口。
 */
internal class UsbAudioHidVolumeMonitor(
    private val connection: UsbDeviceConnection,
    private val hidInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val mapping: UsbHidVolumeMapping,
    private val onVolumeKey: (Int) -> Unit,
) {
    @Volatile private var stopped = false
    private val thread = Thread(::loop, "UsbHidVolumeKeys").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun stop() {
        stopped = true
        thread.interrupt()
        runCatching { thread.join(STOP_TIMEOUT_MS) }
        runCatching { connection.releaseInterface(hidInterface) }
    }

    private fun loop() {
        val decoder = UsbHidVolumeReportDecoder(mapping)
        val packetSize = endpointIn.maxPacketSize.coerceIn(1, 64)
        val buffer = ByteArray(packetSize)
        var consecutiveFastErrors = 0
        while (!stopped) {
            val startedMs = android.os.SystemClock.elapsedRealtime()
            val read = runCatching {
                connection.bulkTransfer(endpointIn, buffer, buffer.size, READ_TIMEOUT_MS)
            }.getOrElse { -1 }
            if (stopped) break
            if (read > 0) {
                consecutiveFastErrors = 0
                val events = decoder.decode(buffer, read)
                events.forEach { direction ->
                    runCatching { onVolumeKey(direction) }
                }
            } else if (read < 0) {
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedMs
                // Android bulkTransfer 正常超时也返回 -1，仅将瞬时失败计为异常（如设备拔出 ENODEV）。
                if (elapsedMs >= READ_TIMEOUT_MS / 2) {
                    consecutiveFastErrors = 0
                } else {
                    consecutiveFastErrors++
                    if (consecutiveFastErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.w(TAG, "USB HID 音量键监听连续快速失败，自动停止")
                        break
                    }
                    runCatching { Thread.sleep(ERROR_BACKOFF_MS) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbAudioDevice"
        private const val READ_TIMEOUT_MS = 200
        private const val STOP_TIMEOUT_MS = 600L
        private const val ERROR_BACKOFF_MS = 20L
        private const val MAX_CONSECUTIVE_ERRORS = 12
        private const val HID_REPORT_DESCRIPTOR_VALUE = 0x2200
        private const val GET_DESCRIPTOR_REQUEST = 0x06
        private const val INTERFACE_IN_REQUEST_TYPE = 0x81

        /**
         * 扫描设备上的 HID 接口，找到第一个声明了 Consumer Control 音量键的 Interrupt IN 端点。
         * 非音量键 HID 接口会立刻释放，不干扰其他功能。
         */
        fun open(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            onVolumeKey: (Int) -> Unit,
        ): UsbAudioHidVolumeMonitor? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                val endpointIn = (0 until iface.endpointCount)
                    .map { iface.getEndpoint(it) }
                    .firstOrNull {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                            it.direction == UsbConstants.USB_DIR_IN
                    } ?: continue
                if (!connection.claimInterface(iface, true)) continue
                val reportDescriptor = ByteArray(512)
                val descLength = runCatching {
                    connection.controlTransfer(
                        INTERFACE_IN_REQUEST_TYPE,
                        GET_DESCRIPTOR_REQUEST,
                        HID_REPORT_DESCRIPTOR_VALUE,
                        iface.id,
                        reportDescriptor,
                        reportDescriptor.size,
                        500,
                    )
                }.getOrDefault(-1)
                if (descLength <= 0) {
                    runCatching { connection.releaseInterface(iface) }
                    continue
                }
                val mapping = parseUsbHidVolumeDescriptor(reportDescriptor.copyOf(descLength))
                if (!mapping.isSupported) {
                    runCatching { connection.releaseInterface(iface) }
                    continue
                }
                Log.i(TAG, "已接管 USB HID 音量键：iface=${iface.id}, ep=0x${endpointIn.address.toString(16)}")
                return UsbAudioHidVolumeMonitor(connection, iface, endpointIn, mapping, onVolumeKey)
            }
            return null
        }
    }
}
