package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperCreditsTest {
    @Test
    fun developersOnlyShowShuyunRAndTheMatchingGitHubAccount() {
        assertEquals(listOf("ShuyunR"), developerCredits.map { it.name })
        assertEquals(listOf("HGODLK"), developerCredits.map { it.account })
        assertEquals(
            listOf("https://github.com/HGODLK"),
            developerCredits.map { it.profileUrl },
        )
    }
}
