package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackPreloadBandwidthTest {
    private fun ns(ms: Long) = ms * 1_000_000L
    private fun slow(bandwidth: PlaybackPreloadBandwidth, uri: String = "media", bytes: Int = 8_000): Long {
        val id = bandwidth.opened(uri)
        bandwidth.beginRead(id, ns(0))
        bandwidth.endRead(id, bytes, ns(1_000))
        bandwidth.beginRead(id, ns(1_000))
        return id
    }

    @Test fun onlyCurrentMediaBodyReadsWithTwoSecondsOfEvidenceCanQualify() {
        val bandwidth = PlaybackPreloadBandwidth()
        slow(bandwidth)
        assertFalse(bandwidth.insufficient(ns(2_500), 128_000))
        bandwidth.mediaLoad(1, "media")
        assertFalse(bandwidth.insufficient(ns(1_999), 128_000))
        assertTrue(bandwidth.insufficient(ns(2_500), 128_000))
        bandwidth.finishMediaLoad(1)
        assertFalse(bandwidth.insufficient(ns(2_500), 128_000))
    }

    @Test fun healthyOrBorderlineThroughputAndUnknownBitratesNeverQualify() {
        for (bytes in listOf(22_400, 24_000, 64_000)) {
            val bandwidth = PlaybackPreloadBandwidth()
            bandwidth.mediaLoad(1, "media")
            slow(bandwidth, bytes = bytes)
            assertFalse(bandwidth.insufficient(ns(2_000), 128_000))
        }
        val bandwidth = PlaybackPreloadBandwidth()
        bandwidth.mediaLoad(1, "media")
        slow(bandwidth)
        assertFalse(bandwidth.insufficient(ns(2_500), 0))
        assertFalse(bandwidth.insufficient(ns(2_500), -1))
    }

    @Test fun connectionWaitAndNoBodyBytesAreNotBandwidthEvidence() {
        val bandwidth = PlaybackPreloadBandwidth()
        bandwidth.mediaLoad(1, "media")
        val id = bandwidth.opened("media")
        assertFalse(bandwidth.insufficient(ns(5_000), 128_000))
        bandwidth.beginRead(id, ns(5_000))
        assertFalse(bandwidth.insufficient(ns(9_000), 128_000))
    }

    @Test fun decoderDiskOrConsumerGapsDoNotIncreaseNetworkReadTime() {
        val bandwidth = PlaybackPreloadBandwidth()
        bandwidth.mediaLoad(1, "media")
        val id = bandwidth.opened("media")
        bandwidth.beginRead(id, ns(0))
        bandwidth.endRead(id, 8_000, ns(1))
        bandwidth.beginRead(id, ns(4_000))
        assertFalse(bandwidth.insufficient(ns(4_001), 128_000))
    }

    @Test fun failuresAndConcurrentTransfersAreRejected() {
        val bandwidth = PlaybackPreloadBandwidth()
        bandwidth.mediaLoad(1, "media")
        val first = slow(bandwidth)
        val second = slow(bandwidth)
        assertFalse(bandwidth.insufficient(ns(2_500), 128_000))
        bandwidth.closed(second)
        assertTrue(bandwidth.insufficient(ns(2_500), 128_000))
        bandwidth.failed(first)
        assertEquals(1L, bandwidth.failureRevision)
        assertFalse(bandwidth.insufficient(ns(2_500), 128_000))
    }

    @Test fun seeksNewMediaAndOldSamplesCannotSupplyEvidence() {
        val bandwidth = PlaybackPreloadBandwidth()
        bandwidth.mediaLoad(1, "media")
        val id = slow(bandwidth)
        bandwidth.resetMeasurements()
        bandwidth.endRead(id, 8_000, ns(3_000))
        assertFalse(bandwidth.insufficient(ns(3_000), 128_000))
        slow(bandwidth)
        bandwidth.newMedia()
        assertFalse(bandwidth.insufficient(ns(3_000), 128_000))
        val fresh = PlaybackPreloadBandwidth()
        fresh.mediaLoad(1, "media")
        slow(fresh)
        assertFalse(fresh.insufficient(ns(8_000), 128_000))
    }
}
