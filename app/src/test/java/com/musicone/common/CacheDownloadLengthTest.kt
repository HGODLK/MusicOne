package com.musicone

import org.junit.Assert.*
import org.junit.Test

class CacheDownloadLengthTest {
    @Test fun fullyCachedDashWithUnknownMetadataGetsAnExactBoundInsteadOfAnEofRequest() {
        val existingEnd = 66_921_355L
        assertEquals(existingEnd, boundedCacheDownloadLength(existingEnd, existingEnd))
    }
    @Test fun partialAudioKeepsTheServerEndForFillingOnlyMissingBytes() {
        assertEquals(1000L, boundedCacheDownloadLength(1000, 640))
        assertEquals(1000L, boundedCacheDownloadLength(1000, 0))
    }
    @Test fun shorterReplacementCannotSilentlyTruncateAnOldCache() {
        assertThrows(PlatformApiException::class.java) { boundedCacheDownloadLength(639, 640) }
    }
    @Test fun changedKnownLengthCannotMixOldPartialBytesWithANewRepresentation() {
        assertThrows(PlatformApiException::class.java) { boundedCacheDownloadLength(1200, 640, 1000) }
        assertThrows(PlatformApiException::class.java) { boundedCacheDownloadLength(800, 640, 1000) }
        assertEquals(1000L, boundedCacheDownloadLength(1000, 640, 1000))
    }
    @Test fun invalidatedProbeCannotPublishConfirmedLength() {
        var writes = 0
        assertThrows(IllegalStateException::class.java) {
            commitCacheDownloadLength(1000, 1000, -1,
                commit = { throw IllegalStateException("实例已移除") }, write = { writes++ })
        }
        assertEquals(0, writes)
        assertEquals(1000L, commitCacheDownloadLength(1000, 640, -1, commit = { it() }, write = { writes++ }))
        assertEquals(1, writes)
    }
    @Test fun unknownOrEmptyRemoteLengthCannotDeclareExistingBytesComplete() {
        listOf(-1L, 0L).forEach { length ->
            assertThrows(PlatformApiException::class.java) { boundedCacheDownloadLength(length, 640) }
        }
    }
}
