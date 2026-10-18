package com.musicone.demo.library

import com.musicone.demo.LocalImportResult
import com.musicone.demo.displaySummary
import com.musicone.demo.isLocalAudioCandidate
import org.junit.Assert.*
import org.junit.Test

class LocalMusicImporterFilterTest {
    @Test fun directoryMetadataArchivesAndArtworkNeverEnterAudioProbe() {
        for (name in listOf(".nomedia", ".database_uuid", ".DS_Store", "backup.msco",
                "lyrics.lrc", "info.txt", "cover.jpg", "cover.png", "track.cue", "meta.json")) {
            assertFalse(name, isLocalAudioCandidate(name, "audio/*"))
        }
    }

    @Test fun newLosslessFormatsRemainCandidatesWithUnhelpfulDocumentMime() {
        for (extension in listOf("mp3", "flac", "wav", "ape", "wv", "dsf", "dff", "m4a")) {
            assertTrue(extension, isLocalAudioCandidate("歌曲.$extension", "application/octet-stream"))
        }
        assertTrue(isLocalAudioCandidate(".hidden.FLAC", null))
        assertTrue(isLocalAudioCandidate("无扩展名歌曲", "audio/flac"))
        assertTrue(isLocalAudioCandidate("歌曲.MP3", null))
        assertFalse(isLocalAudioCandidate("未知格式.bin", "application/octet-stream"))
    }

    @Test fun summaryDoesNotLeakFilenameOrExceptionAndOnlyCountsFailures() {
        val result = LocalImportResult(12, 0,
            listOf("private.mp3: setDataSource failed 0x80000000", "corrupt.flac: invalid stream"))
        assertEquals("已导入 12 首 · 已存在 0 首 · 2 项未导入", result.displaySummary())
        assertEquals("已导入 12 首 · 已存在 0 首", result.copy(failed = emptyList()).displaySummary())
    }
}
