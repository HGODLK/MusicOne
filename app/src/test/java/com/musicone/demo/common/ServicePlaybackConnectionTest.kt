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

    @Test fun commandAfterDismissalRequestsANewServiceAndKeepsLatestIntent() {
        var reconnects = 0
        val connection = ServicePlaybackConnection { reconnects++ }
        val old = player()
        val replacement = player()
        connection.attach(old)
        connection.detach(old)
        var shouldPlay = true
        val applied = mutableListOf<Pair<Player, Boolean>>()
        connection.whenReady { applied += it to shouldPlay }
        shouldPlay = false
        connection.whenReady { applied += it to shouldPlay }
        assertEquals(2, reconnects)
        connection.attach(replacement)
        assertEquals(2, applied.size)
        assertTrue(applied.all { it.first === replacement && !it.second })
        connection.detach(old)
        assertSame(replacement, connection.player)
    }

    @Test fun synchronousReconnectCannotLoseTheCommandThatRequestedIt() {
        val replacement = player()
        lateinit var connection: ServicePlaybackConnection
        connection = ServicePlaybackConnection { connection.attach(replacement) }
        var received: Player? = null
        connection.whenReady { received = it }
        assertSame(replacement, received)
    }

    @Test fun releaseRejectsLateCommandsAndLateServiceAttachments() {
        var reconnects = 0
        val connection = ServicePlaybackConnection { reconnects++ }
        connection.release()
        connection.whenReady { fail("已关闭连接不能执行新操作") }
        connection.attach(player())
        assertNull(connection.player)
        assertEquals(0, reconnects)
    }
}
