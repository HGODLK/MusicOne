package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperCreditsTest {
    @Test
    fun developersKeepRequestedOrderAndGitHubAccounts() {
        assertEquals(listOf("ShuyunR", "Killy"), developerCredits.map { it.name })
        assertEquals(listOf("HGODLK", "Killy806"), developerCredits.map { it.account })
        assertEquals(
            listOf("https://github.com/HGODLK", "https://github.com/Killy806"),
            developerCredits.map { it.profileUrl },
        )
    }
}
