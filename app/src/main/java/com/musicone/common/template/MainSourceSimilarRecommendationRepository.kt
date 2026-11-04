package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceCursorRequest
import com.musicone.sourceintegration.*

/** 相似推荐标题与曲目由包完整交付，模板不解释远端编号。 */
internal class MainSourceSimilarRecommendationRepository(private val context: Context) {
    suspend fun load(baseTrack: MusicTrack, amount: Int = MAIN_SOURCE_SIMILAR_SIZE): MainSourceSimilarRecommendation {
        val discovery = requireNotNull(SourcePackageController.get(context).running(baseTrack.source.id)?.entry?.discovery)
        check(discovery.supportsSimilar) { "此音源不支持相似推荐" }
        val result = discovery.similarSection(baseTrack.toSourceTrack(), SourceCursorRequest(limit = amount))
        return MainSourceSimilarRecommendation(baseTrack, result.title, result.tracks.map { MainSourceSimilarSong(it.toMusicTrack()) })
    }
}
