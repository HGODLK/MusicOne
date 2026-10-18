package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class BiliLibraryPresentationTest {
    private val folder = BiliFavFolder(42, "收藏夹", 40, "https://example.test/folder.jpg")
    private fun video(id: Int, invalid: Boolean = false) =
        BiliVideoItem(id.toLong(), "BV$id", 0, "视频$id", "https://example.test/$id.jpg", "创作者", 60, invalid)

    @Test fun signedOutCannotDisplayAnyContentDestination() {
        BiliLibraryPage.entries.forEach { page ->
            assertEquals(BiliLibraryPage.ACCOUNT, BiliLibraryUiState(page = page).visiblePage())
        }
        assertEquals(BiliLibraryPage.FOLDER, BiliLibraryUiState(page = BiliLibraryPage.FOLDER,
            account = BiliAccountSession("fixture", "42", "用户")).visiblePage())
    }

    @Test fun folderCardsRetainTheRealArtwork() {
        assertEquals(folder.cover, folder.asPlaylist().artworkUrl)
        assertEquals("40 个视频", folder.asPlaylist().subtitle)
    }

    @Test fun playbackSnapshotKeepsCoversAndFiltersInvalidVideosWithoutExpandingParts() {
        val playlist = BiliFolderSnapshot(listOf(video(1), video(2), video(3), video(4, invalid = true)), 2, false).asPlaylist(folder)
        assertEquals(listOf("BV1", "BV2", "BV3"), playlist.tracks.map { it.bilibiliBvid })
        assertEquals("https://example.test/1.jpg", playlist.tracks.first().artworkUrl)
        assertEquals(0L, playlist.tracks.first().bilibiliCid)
    }

}
