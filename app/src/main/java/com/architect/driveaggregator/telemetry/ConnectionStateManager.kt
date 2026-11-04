package com.architect.driveaggregator.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Manages telemetry connection states: DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, OFFLINE.
 * Implements exponential backoff with jitter for reconnection attempts.
 */
class ConnectionStateManager(
    private val onStateChanged: (ConnectionState) -> Unit = {},
    private val onReconnectAttempt: suspend () -> Boolean
) {
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, OFFLINE
    }

    @Volatile
    private var currentState: ConnectionState = ConnectionState.DISCONNECTED
    fun getState(): ConnectionState = currentState

    private var consecutiveFailures = 0
    fun getConsecutiveFailures(): Int = consecutiveFailures

    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initialDelayMs = 1000L
    private val maxDelayMs = 30000L
    private val maxFailuresBeforeOffline = 5

    fun updateState(newState: ConnectionState) {
        if (currentState != newState) {
            currentState = newState
            onStateChanged(newState)
        }
    }

    fun start() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (true) {
                if (currentState == ConnectionState.CONNECTED) {
                    delay(60_000)
                    continue
                }

                updateState(ConnectionState.CONNECTING)
                val success = try {
                    onReconnectAttempt()
                } catch (_: Exception) {
                    false
                }

                if (success) {
                    consecutiveFailures = 0
                    updateState(ConnectionState.CONNECTED)
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= maxFailuresBeforeOffline) {
                        updateState(ConnectionState.OFFLINE)
                    } else {
                        updateState(ConnectionState.DEGRADED)
                    }

                    val exponential = min(maxDelayMs, initialDelayMs * (1L shl min(consecutiveFailures, 6)))
                    val jitter = (exponential * 0.2 * (Math.random() - 0.5)).toLong()
                    delay(maxOf(200L, exponential + jitter))
                }
            }
        }
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        updateState(ConnectionState.DISCONNECTED)
    }

    fun reset() {
        stop()
        consecutiveFailures = 0
    }
}
