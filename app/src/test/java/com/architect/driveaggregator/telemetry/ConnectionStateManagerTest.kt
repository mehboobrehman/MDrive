package com.architect.driveaggregator.telemetry

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateManagerTest {

    @Test
    fun testInitialState() {
        val manager = ConnectionStateManager(
            onReconnectAttempt = { true }
        )
        assertEquals(ConnectionStateManager.ConnectionState.DISCONNECTED, manager.getState())
        assertEquals(0, manager.getConsecutiveFailures())
    }

    @Test
    fun testManualStateTransition() {
        var lastState: ConnectionStateManager.ConnectionState? = null
        val manager = ConnectionStateManager(
            onStateChanged = { state -> lastState = state },
            onReconnectAttempt = { true }
        )
        
        manager.updateState(ConnectionStateManager.ConnectionState.CONNECTING)
        assertEquals(ConnectionStateManager.ConnectionState.CONNECTING, manager.getState())
        assertEquals(ConnectionStateManager.ConnectionState.CONNECTING, lastState)

        manager.updateState(ConnectionStateManager.ConnectionState.CONNECTED)
        assertEquals(ConnectionStateManager.ConnectionState.CONNECTED, manager.getState())
        assertEquals(ConnectionStateManager.ConnectionState.CONNECTED, lastState)

        manager.updateState(ConnectionStateManager.ConnectionState.DEGRADED)
        assertEquals(ConnectionStateManager.ConnectionState.DEGRADED, manager.getState())
        assertEquals(ConnectionStateManager.ConnectionState.DEGRADED, lastState)

        manager.updateState(ConnectionStateManager.ConnectionState.OFFLINE)
        assertEquals(ConnectionStateManager.ConnectionState.OFFLINE, manager.getState())
        assertEquals(ConnectionStateManager.ConnectionState.OFFLINE, lastState)
    }

    @Test
    fun testReset() {
        val manager = ConnectionStateManager(
            onReconnectAttempt = { true }
        )
        manager.updateState(ConnectionStateManager.ConnectionState.CONNECTED)
        manager.reset()
        assertEquals(ConnectionStateManager.ConnectionState.DISCONNECTED, manager.getState())
        assertEquals(0, manager.getConsecutiveFailures())
    }
}
