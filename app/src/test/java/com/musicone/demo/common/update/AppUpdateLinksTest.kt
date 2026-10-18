package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class AppUpdateLinksTest {
    @Test fun updateQueriesUseWorkerAndPaginationNeverUsesDownloadAcceleration() {
        assertEquals("https://dl.shuyunrainbow.cn/api/update?page=1", updateReleasePageUrl(1))
        assertEquals("https://dl.shuyunrainbow.cn/api/update?page=2", updateReleasePageUrl(2))
    }

    @Test fun downloadPreservesEncodedNicknameAndUsesTheDocumentedGatewayRoute() {
        val original = "${MUSIC_ONE_DOWNLOAD_ROOT}v78.78.78-%E5%A4%A7%E7%9A%87%E5%A0%A1/MusicOne-78.78.78-大皇堡.apk"
        assertEquals("https://dl.shuyunrainbow.cn/$original", updateDownloadGatewayUrl(original))
        assertEquals("https://github.com/HGODLK/MusicOne", MUSIC_ONE_GITHUB_PAGE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignRepositoryCannotBeSentToTheDownloadGateway() {
        updateDownloadGatewayUrl("https://github.com/other/repo/releases/download/v1/app.apk")
    }
}
