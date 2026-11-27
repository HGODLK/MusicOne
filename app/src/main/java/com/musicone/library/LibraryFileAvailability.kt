package com.musicone

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

internal data class LibraryFileAvailability(val id: String, val uri: String, val readable: Boolean)

/** 只检查文件权限和能不能打开，不解码音频；ContentProvider 临时报错不当成文件丢失。 */
internal suspend fun inspectLibraryFiles(context: Context, tracks: List<MusicTrack>): List<LibraryFileAvailability> = withContext(Dispatchers.IO) {
    tracks.mapNotNull { track ->
        currentCoroutineContext().ensureActive()
        val readable = if (track.localUri.isBlank()) false else try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(track.localUri), "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) { false }
        catch (_: SecurityException) { false }
        catch (_: IllegalArgumentException) { false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
        catch (_: Exception) { null }
        if (readable != null && readable != track.playable) LibraryFileAvailability(track.id, track.localUri, readable) else null
    }
}
