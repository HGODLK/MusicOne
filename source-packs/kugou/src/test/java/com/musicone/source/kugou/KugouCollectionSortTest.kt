package com.musicone.source.kugou

import com.musicone.source.sdk.SourceTrack
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class KugouCollectionSortTest {
    private fun track(title: String) = SourceTrackDetails(SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", title), title), "kugou-$title")
    private val tracks = listOf(track("c"), track("a"), track("b"))
    private fun titles(sortId: String?) = sortedKugouCollectionTracks(tracks, sortId).map { it.track.title }

    @Test fun 默认保持接口顺序() {
        assertEquals(listOf("c", "a", "b"), titles("ADDED_DESC"))
        assertEquals(listOf("c", "a", "b"), titles(null))
    }

    @Test fun 旧到新是接口顺序的倒序() = assertEquals(listOf("b", "a", "c"), titles("ADDED_ASC"))

    @Test fun 按名称排序() = assertEquals(listOf("a", "b", "c"), titles("NAME"))
}
