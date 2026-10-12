package com.musicone.demo

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ServicePlaybackConnectionTest {
    private fun player(): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java),
    ) { _, _, _ -> null } as Player

    @Test fun readyCallbacksRunOnceAndNewServiceReceivesOnlyPendingCommands() {
        val connection = ServicePlaybackConnection()
        val first = player()
        val second = player()
        val received = mutableListOf<Player>()
        connection.whenReady { received.add(it) }
        connection.attach(first)
        assertEquals(1, received.size)
        assertSame(first, received[0])
        connection.whenReady { received.add(it) }
        assertSame(first, received[1])
        connection.detach(second)
        assertSame(first, connection.player)
        connection.detach(first)
        connection.whenReady { received.add(it) }
        connection.attach(second)
        assertEquals(3, received.size)
        assertSame(second, received[2])
        connection.release()
        assertNull(connection.player)
    }

    @Test fun finalReleaseDiscardsCommandsWaitingForAService() {
        val connection = ServicePlaybackConnection()
        var calls = 0
        connection.whenReady { calls++ }
        connection.release()
        connection.attach(player())
        assertEquals(0, calls)
    }
}
