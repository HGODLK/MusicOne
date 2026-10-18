package com.musicone.nativeaudio

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 将媒体读取桥接到 Native FFmpeg AVIO 的数据读取与定位。
 * 对 file:// 与 content:// 优先采用系统级 FileChannel 快速 Seek，耗时为零且不反复重开流；
 * 未知数据流无缝回退至 Media3 DataSource。
 */
@Keep
@OptIn(UnstableApi::class)
class NativeAudioIoBridge(
    private val dataSource: DataSource,
    private val initialDataSpec: DataSpec,
    private val context: Context? = null,
) : AutoCloseable {

    private var parcelFd: ParcelFileDescriptor? = null
    private var fileChannel: FileChannel? = null
    private var fileInputStream: FileInputStream? = null
    private var isUsingChannel = false

    private var currentPosition: Long = 0L
    private var totalLength: Long = -1L
    private var isFallbackOpen = false
    private val lock = Any()

    init {
        synchronized(lock) {
            setupChannel()
        }
    }

    private fun setupChannel() {
        val uri = initialDataSpec.uri
        runCatching {
            val pfd = when (uri.scheme?.lowercase()) {
                "file" -> {
                    val file = File(uri.path.orEmpty())
                    if (file.exists() && file.canRead()) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else null
                }
                "content" -> {
                    context?.contentResolver?.openFileDescriptor(uri, "r")
                }
                else -> null
            }

            if (pfd != null) {
                parcelFd = pfd
                val fis = FileInputStream(pfd.fileDescriptor)
                fileInputStream = fis
                val channel = fis.channel
                fileChannel = channel
                totalLength = channel.size()
                currentPosition = 0L
                channel.position(0L)
                isUsingChannel = true
                return
            }
        }

        // 回退至通用 DataSource
        isUsingChannel = false
        openAtFallback(0L)
    }

    private fun openAtFallback(position: Long): Long {
        if (isFallbackOpen) {
            runCatching { dataSource.close() }
            isFallbackOpen = false
        }
        val spec = initialDataSpec.buildUpon()
            .setPosition(position)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        val openedLength = dataSource.open(spec)
        isFallbackOpen = true
        currentPosition = position
        if (openedLength != C.LENGTH_UNSET.toLong()) {
            totalLength = position + openedLength
        }
        return currentPosition
    }

    @Keep
    fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (isUsingChannel) {
            val channel = fileChannel ?: return -1
            try {
                val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
                val read = channel.read(byteBuffer)
                if (read > 0) {
                    currentPosition += read
                }
                return read
            } catch (e: IOException) {
                return -1
            }
        } else {
            if (!isFallbackOpen) return -1
            try {
                val bytesRead = dataSource.read(buffer, offset, length)
                if (bytesRead > 0) {
                    currentPosition += bytesRead
                }
                return bytesRead
            } catch (e: IOException) {
                return -1
            }
        }
    }

    @Keep
    fun seek(position: Long): Long = synchronized(lock) {
        if (position < 0) return -1L
        if (isUsingChannel) {
            val channel = fileChannel ?: return -1L
            return try {
                channel.position(position)
                currentPosition = position
                currentPosition
            } catch (e: Exception) {
                -1L
            }
        } else {
            if (position == currentPosition) return currentPosition
            return try {
                openAtFallback(position)
            } catch (e: Exception) {
                -1L
            }
        }
    }

    @Keep
    fun getSize(): Long = synchronized(lock) {
        return totalLength
    }

    override fun close() = synchronized(lock) {
        if (isUsingChannel) {
            runCatching { fileChannel?.close() }
            runCatching { fileInputStream?.close() }
            runCatching { parcelFd?.close() }
            fileChannel = null
            fileInputStream = null
            parcelFd = null
            isUsingChannel = false
        } else {
            if (isFallbackOpen) {
                runCatching { dataSource.close() }
                isFallbackOpen = false
            }
        }
    }
}
