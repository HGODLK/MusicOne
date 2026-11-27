package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceCursorRequest
import com.musicone.sourceintegration.*

/** 拉取单首种子的相似推荐，标题和歌曲列表都直接用音源包返回的结果。 */
internal class MainSourceSimilarRecommendationRepository(private val context: Context) {
    suspend fun load(baseTrack: MusicTrack, amount: Int = MAIN_SOURCE_SIMILAR_SIZE): MainSourceSimilarRecommendation {
        val discovery = requireNotNull(SourcePackageController.get(context).running(baseTrack.source.id)?.entry?.discovery)
        check(discovery.supportsSimilar) { "此音源不支持相似推荐" }
        val result = discovery.similarSection(baseTrack.toSourceTrack(), SourceCursorRequest(limit = amount))
        return MainSourceSimilarRecommendation(baseTrack, result.title, result.tracks.map { MainSourceSimilarSong(it.toMusicTrack()) })
    }
}
