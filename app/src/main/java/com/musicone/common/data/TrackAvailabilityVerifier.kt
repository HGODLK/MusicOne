package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceOperationException
import com.musicone.sourceintegration.*
import kotlinx.coroutines.CancellationException

/** 只有包明确返回不可播放时才置灰，网络及验证失败仍保留重试。 */
internal class TrackAvailabilityVerifier(private val context: Context) {
    suspend fun verify(track: MusicTrack): String? = try {
        val entry = SourcePackageController.get(context).running(track.source.id)?.entry
        entry?.let { refreshedSourceTrackAccess(it, track.toSourceTrack()) }?.let { if (it.playable) null else it.reason ?: "此条目暂不可用" }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SourceOperationException) { failure.message.takeIf { failure.code == "TRACK_UNAVAILABLE" } }
    catch (_: Exception) { null }
}
