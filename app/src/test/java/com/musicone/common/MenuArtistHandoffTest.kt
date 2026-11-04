package com.musicone

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class MenuArtistHandoffTest {
    @Test fun sourceAndRetainedAvatarHaveTheSameRootPositionAcrossHosts() {
        val avatar = Rect(29f, 230f, 61f, 262f)
        for (host in listOf(Rect(0f, 0f, 400f, 800f), Rect(0f, 32f, 400f, 800f), Rect(100f, 48f, 1200f, 900f))) {
            val offset = menuArtistHandoffOffset(avatar, host, 0f)
            assertEquals(avatar.left, host.left + offset.x, 0f)
            assertEquals(avatar.top, host.top + offset.y, 0f)
            val moving = menuArtistHandoffOffset(avatar, host, -8f)
            assertEquals(avatar.top - 8f, host.top + moving.y, 0f)
        }
    }
}
