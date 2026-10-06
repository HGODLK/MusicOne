package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerArtworkStateTest {
    private fun track(id: String, url: String? = "https://example.com/$id.jpg") = MusicTrack(
        id, MusicSource.NETEASE, "歌曲$id", "歌手", "专辑", 200_000L,
        0xFF112233, 0xFF445566, "", "", artworkUrl = url,
    )

    @Test fun playbackAndLyricsUpdatesDoNotRestartArtworkTransition() {
        val original = track("a")
        val resolved = original.copy(previewUrl = "https://example.com/audio", lyrics = listOf(TimedLyric(0, "歌词")))
        assertEquals(original.artworkIdentity(), resolved.artworkIdentity())
        assertNotEquals(original.artworkIdentity(), original.copy(artworkUrl = "https://example.com/new.jpg").artworkIdentity())
    }

    @Test fun atmosphereUsesThreeByThreeArtworkSampling() {
        val colors = ArtworkColorSampler.placeholder(track("grid").artworkIdentity())
        assertEquals(3, ArtworkColorSampler.GRID_SIDE)
        assertEquals(9, colors.size)
        assertNotEquals(colors.first(), colors.last())
    }

    @Test fun realArtworkColorsReplacePlaceholderForTheSameIdentity() {
        val identity = track("same-cover").artworkIdentity()
        val placeholder = intArrayOf(1, 2, 3)
        val sampled = intArrayOf(4, 5, 6)

        assertTrue(artworkAtmospherePaletteChanged(identity, placeholder, identity, sampled))
        assertFalse(artworkAtmospherePaletteChanged(identity, sampled, identity, sampled.copyOf()))
        assertTrue(artworkAtmospherePaletteChanged(
            identity, sampled, track("next-cover").artworkIdentity(), sampled,
        ))
    }

    @Test fun atmospherePhaseAdvancesOnlyOnStablePlayerPage() {
        val motion = PlayerAtmosphereMotionState()
        val initial = motion.phase
        assertFalse(playerAtmosphereCanAdvance(MotionPhase.MOVING))
        assertFalse(playerAtmosphereCanAdvance(MotionPhase.PREPARING))
        assertTrue(playerAtmosphereCanAdvance(MotionPhase.SHOWN))
        motion.advance(1_000_000_000L)
        assertTrue(motion.phase > initial)
    }

    @Test fun atmospherePhaseAndIndependentBlobPathsStayContinuousAcrossOldCycleBoundary() {
        val before = Math.PI * 2.0 - .001
        val after = nextPlayerAtmospherePhase(before, 20_000_000L)
        assertTrue(after > Math.PI * 2.0)
        val blob = atmosphereBlobMotions().first()
        val first = atmosphereBlobCenter(blob, before, 1_000f, 1_600f)
        val second = atmosphereBlobCenter(blob, after, 1_000f, 1_600f)
        assertTrue((second - first).getDistance() < 5f)
    }

    @Test fun prefetchWrapsBothQueueEndsAndDeduplicates() {
        val queue = listOf(track("a"), track("b"), track("c"))
        assertEquals(listOf(queue[0].artworkUrl, queue[1].artworkUrl, queue[2].artworkUrl), adjacentArtworkUrls(queue[0], queue))
        assertEquals(listOf(queue[2].artworkUrl, queue[0].artworkUrl, queue[1].artworkUrl), adjacentArtworkUrls(queue[2], queue))
        assertEquals(listOf(queue[0].artworkUrl), adjacentArtworkUrls(queue[0], listOf(queue[0])))
    }

    @Test fun prefetchHandlesEmptyQueuesMissingCoversAndSharedArtwork() {
        assertTrue(adjacentArtworkUrls(null, emptyList()).isEmpty())
        assertEquals(listOf(track("a").artworkUrl), adjacentArtworkUrls(track("a"), emptyList()))
        val current = track("a")
        assertEquals(listOf(current.artworkUrl), adjacentArtworkUrls(current,
            listOf(current, track("b", current.artworkUrl), track("c", null))))
    }
}
