package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricBoundarySamplingTest {
    @Test fun landscapeWrappedBodyAndTranslationExitOneLayoutLineAtATime() {
        val edges = LyricBoundaryEdges(0f, 200f)
        val body = LyricBoundaryAlphaSampler(listOf(0f to 40f, 48f to 88f))
        val translation = LyricBoundaryAlphaSampler(listOf(0f to 26f, 32f to 58f))
        assertEquals(listOf(1f, 1f), body.sample(-39f, 1f, edges, 8f, partialTop = true))
        assertEquals(listOf(0f, 1f), body.sample(-40f, 1f, edges, 8f, partialTop = true))
        assertEquals(listOf(0f, 1f), translation.sample(-30f, 1f, edges, 8f, partialTop = true))
        assertEquals(listOf(0f, 0f), translation.sample(-58f, 1f, edges, 8f, partialTop = true))
    }

    @Test fun landscapeFastPathMatchesAllTransformedLinesAtTheTopAndBottom() {
        val bands = listOf(0f to 40f, 48f to 90f, 98f to 146f)
        val sampler = LyricBoundaryAlphaSampler(bands)
        for (scale in listOf(.925f, 1f, 2f)) for (origin in -300..220) {
            val edges = LyricBoundaryEdges(0f, 200f)
            val expected = bands.map { (top, bottom) ->
                lyricBoundaryLineAlpha(origin + top * scale, origin + bottom * scale,
                    0f, 200f, 8f, partialTop = true)
            }
            assertEquals(expected, sampler.sample(origin.toFloat(), scale, edges, 8f, partialTop = true))
        }
    }

    @Test fun expandedBoundaryReadsCurrentPaneAndTrackTranslationTogether() {
        var paneShift = 0f
        var trackShift = 0f
        var progress = 0f
        val viewport = LyricBoundaryViewport(LyricBoundaryWindow(40f,
            offset = { paneShift + trackShift },
            edges = { height, shift -> LyricBoundaryEdges((340f - shift) * progress,
                height - (400f + shift) * progress) }, partialTop = true), { 1500f })
        assertEquals(LyricBoundaryEdges(0f, 1500f), viewport.read())
        progress = 1f
        paneShift = 20f
        trackShift = 30f
        assertEquals(LyricBoundaryEdges(290f, 1050f), viewport.read())
    }

    @Test fun fastPathMatchesEveryOriginalLineAcrossMotionAndViewportChanges() {
        val paragraphs = listOf(emptyList(), listOf(0f to 40f),
            listOf(0f to 40f, 48f to 90f), List(30) { it * 43f to it * 43f + 41f })
        for (bands in paragraphs) {
            val sampler = LyricBoundaryAlphaSampler(bands)
            for (height in listOf(180f, 680f, 1600f)) for (shift in listOf(-20f, 0f, 120f, 900f)) {
                val edges = LyricBoundaryEdges((120f - shift).coerceAtLeast(0f),
                    (height - shift).coerceAtMost(height))
                for (scale in listOf(-1f, 0f, .925f, 1f, 2.75f)) for (step in -16..180) {
                    val origin = step * 8.25f
                    val expected = bands.map { (top, bottom) ->
                        lyricBoundaryLineAlpha(origin + top * scale, origin + bottom * scale,
                            edges.top, edges.bottom, 8f)
                    }
                    assertEquals(expected, sampler.sample(origin, scale, edges, 8f))
                }
            }
        }
    }

    @Test fun clearParagraphReusesValuesButReversalStillFadesAtTheBoundary() {
        val sampler = LyricBoundaryAlphaSampler(listOf(0f to 40f, 48f to 88f))
        val edges = LyricBoundaryEdges(0f, 400f)
        val clear = sampler.sample(20f, 1f, edges, 8f)
        assertSame(clear, sampler.sample(60f, .925f, edges, 8f))
        assertEquals(listOf(.5f, 1f), sampler.sample(4f, 1f, edges, 8f))
        assertSame(clear, sampler.sample(20f, 1f, edges, 8f))
        assertEquals(listOf(0f, 0f), sampler.sample(401f, 1f, edges, 8f))
    }

    @Test fun viewportReadsOrdinaryLayoutFieldsEvenWithoutSnapshotInvalidation() {
        var offset = 0f
        var height = 680f
        val viewport = LyricBoundaryViewport(LyricBoundaryWindow(120f, offset = { offset }), { height })
        assertEquals(LyricBoundaryEdges(120f, 680f), viewport.read())
        offset = 160f
        assertEquals(LyricBoundaryEdges(0f, 520f), viewport.read())
        height = 340f
        assertEquals(LyricBoundaryEdges(0f, 180f), viewport.read())
        offset = 0f
        assertEquals(LyricBoundaryEdges(120f, 340f), viewport.read())
    }
}
