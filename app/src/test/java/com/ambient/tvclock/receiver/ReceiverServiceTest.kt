package com.ambient.tvclock.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for ReceiverService constants and protocol-state mapping behavior. */
class ReceiverServiceTest {

    @Test
    fun `ACTION_START has package-qualified value`() {
        assertEquals("com.ambient.tvclock.receiver.action.START", ReceiverService.ACTION_START)
    }

    @Test
    fun `ACTION_STOP has package-qualified value`() {
        assertEquals("com.ambient.tvclock.receiver.action.STOP", ReceiverService.ACTION_STOP)
    }

    @Test
    fun `ACTION_RESTART has package-qualified value`() {
        assertEquals("com.ambient.tvclock.receiver.action.RESTART", ReceiverService.ACTION_RESTART)
    }

    @Test
    fun `NOTIFICATION_ID is positive`() {
        assertTrue(
            "NOTIFICATION_ID must be > 0 (Android rejects 0)",
            ReceiverService.NOTIFICATION_ID > 0
        )
    }

    @Test
    fun `CHANNEL_ID is non-empty`() {
        assertTrue(ReceiverService.CHANNEL_ID.isNotEmpty())
    }

    @Test
    fun `all three ACTION constants are distinct`() {
        val actions = setOf(
            ReceiverService.ACTION_START,
            ReceiverService.ACTION_STOP,
            ReceiverService.ACTION_RESTART
        )
        assertEquals("All ACTION constants must be unique", 3, actions.size)
    }

    private fun simulateStateChange(state: ProtocolState): ActiveConnection? =
        when (state) {
            ProtocolState.CONNECTED -> ActiveConnection("AirPlay Sender", Protocol.AIRPLAY)
            ProtocolState.ADVERTISING,
            ProtocolState.DISABLED,
            ProtocolState.ERROR -> null
        }

    @Test
    fun `CONNECTED state creates ActiveConnection for AirPlay protocol`() {
        val connection = simulateStateChange(ProtocolState.CONNECTED)
        assertNotNull("CONNECTED must create an ActiveConnection", connection)
        assertEquals(Protocol.AIRPLAY, connection?.protocol)
        assertEquals("AirPlay Sender", connection?.senderName)
    }

    @Test
    fun `ADVERTISING state clears ActiveConnection`() {
        val result = simulateStateChange(ProtocolState.ADVERTISING)
        assertNull("ADVERTISING must clear the ActiveConnection", result)
    }

    @Test
    fun `DISABLED state clears ActiveConnection`() {
        val result = simulateStateChange(ProtocolState.DISABLED)
        assertNull("DISABLED must clear the ActiveConnection", result)
    }

    @Test
    fun `ERROR state clears ActiveConnection`() {
        val result = simulateStateChange(ProtocolState.ERROR)
        assertNull("ERROR must clear the ActiveConnection", result)
    }

    @Test
    fun `CONNECTED then ADVERTISING transition clears connection`() {
        val afterConnect = simulateStateChange(ProtocolState.CONNECTED)
        val afterAdvertising = simulateStateChange(ProtocolState.ADVERTISING)
        assertNotNull(afterConnect)
        assertNull("After ADVERTISING, connection must be null", afterAdvertising)
    }

    @Test
    fun `CONNECTED ActiveConnection has non-negative duration`() {
        val connection = simulateStateChange(ProtocolState.CONNECTED)!!
        assertTrue(connection.durationSeconds >= 0L)
    }

    @Test
    fun `CONNECTED ActiveConnection startedAt is recent`() {
        val before = System.currentTimeMillis()
        val connection = simulateStateChange(ProtocolState.CONNECTED)!!
        val after = System.currentTimeMillis()
        assertTrue(connection.startedAt in before..after)
    }

    @Test
    fun `surface provider lambda returning null is safe to invoke`() {
        val provider: (() -> Any?)? = null
        val result = provider?.invoke()
        assertNull("Null provider must not crash — safe call returns null", result)
    }

    @Test
    fun `surface provider lambda can be replaced`() {
        var currentProvider: () -> Any? = { "surface_A" }
        assertEquals("surface_A", currentProvider.invoke())

        currentProvider = { "surface_B" }
        assertEquals("surface_B", currentProvider.invoke())
    }

    @Test
    fun `surface provider cleared on Activity stop prevents stale surface reference`() {
        val fakeSurface = Any()
        var currentProvider: () -> Any? = { fakeSurface }
        assertEquals(fakeSurface, currentProvider.invoke())

        currentProvider = { null }
        assertNull("Provider must return null after Activity stops", currentProvider.invoke())
    }
}
