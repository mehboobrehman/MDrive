package com.architect.driveaggregator.telemetry

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AgentLoggerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockPrefs.getString(anyString(), any())).thenReturn(null)
        
        // Reset AgentLogger state before each test
        AgentLogger.resetForTesting()
    }

    @After
    fun tearDown() {
        AgentLogger.resetForTesting()
    }

    @Test
    fun logToAgent_doesNotThrowWhenServerIsUnreachable() {
        // Logging without discovery initialized should safely buffer or fail silently
        AgentLogger.logToAgent("INFO", "Unit test telemetry event", "MockStack: line 1")
        AgentLogger.logToAgent("WARN", "Warning event")
        AgentLogger.logToAgent("ERROR", "Error event", "ErrorStack: test error")
        AgentLogger.logToAgent("DEBUG", "Debug event")
        assertNotNull(AgentLogger)
    }

    @Test
    fun initDynamicConfig_handlesSavedIpGracefully() {
        `when`(mockPrefs.getString(anyString(), any())).thenReturn("192.168.1.100")
        AgentLogger.initDynamicConfig(mockContext)
        assertNotNull(AgentLogger)
    }

    @Test
    fun setServerAddress_setsUrlAndPersistsIp() {
        AgentLogger.setServerAddress("192.168.105.79", 8080, mockContext)
        assertEquals("http://192.168.105.79:8080", AgentLogger.getServerUrl())
        assertEquals("192.168.105.79", AgentLogger.getServerIp(mockContext))
        assertTrue(AgentLogger.isConfigured())
        verify(mockEditor).putString("server_ip", "192.168.105.79")
    }

    @Test
    fun setServerAddress_customPort() {
        AgentLogger.setServerAddress("10.0.0.5", 9090, mockContext)
        assertEquals("http://10.0.0.5:9090", AgentLogger.getServerUrl())
        assertEquals("10.0.0.5", AgentLogger.getServerIp(mockContext))
        assertTrue(AgentLogger.isConfigured())
    }

    @Test
    fun featureFlagsAndForce401DefaultToFalse() {
        assertEquals(false, AgentLogger.force401)
        assertTrue(AgentLogger.featureFlags.isEmpty())
    }

    @Test
    fun testConnectionStateInitial() {
        assertEquals(ConnectionStateManager.ConnectionState.DISCONNECTED, AgentLogger.getConnectionState())
    }

    @Test
    fun testSessionIdAndClockOffset() {
        assertNotNull(AgentLogger.getSessionId())
        assertTrue(AgentLogger.getSessionId().isNotBlank())
        assertEquals(0L, AgentLogger.getClockOffsetMs())
        assertEquals(0, AgentLogger.getDroppedEventsCount())
    }

    @Test
    fun testSamplingRateConfig() {
        AgentLogger.setSamplingRate("DIAGNOSTIC", 0.5)
        // Verified via no exception and working configuration
        assertNotNull(AgentLogger)
    }

    @Test
    fun testRemoteControlCommandsAllowList() {
        // Valid allow-listed commands
        val validSetLevel = "{\"action\":\"set_log_level\",\"params\":{\"level\":\"DEBUG\"}}"
        assertTrue(AgentLogger.handleRemoteCommand(validSetLevel))

        val validToggle = "{\"action\":\"toggle_category\",\"params\":{\"category\":\"NETWORK\",\"enabled\":false}}"
        assertTrue(AgentLogger.handleRemoteCommand(validToggle))

        val validSampling = "{\"action\":\"set_sampling_rate\",\"params\":{\"category\":\"EXECUTION\",\"rate\":0.2}}"
        assertTrue(AgentLogger.handleRemoteCommand(validSampling))

        val validSnapshot = "{\"action\":\"request_snapshot\"}"
        assertTrue(AgentLogger.handleRemoteCommand(validSnapshot))

        val validForce401 = "{\"action\":\"set_force_401\",\"params\":{\"enabled\":true}}"
        assertTrue(AgentLogger.handleRemoteCommand(validForce401))
        assertTrue(AgentLogger.force401)

        val validClearLogs = "{\"action\":\"clear_logs\"}"
        assertTrue(AgentLogger.handleRemoteCommand(validClearLogs))

        val validSync = "{\"action\":\"trigger_sync\"}"
        assertTrue(AgentLogger.handleRemoteCommand(validSync))

        // Unknown / un-allowlisted command should be rejected
        val unknownCmd = "{\"action\":\"delete_database\"}"
        assertTrue(!AgentLogger.handleRemoteCommand(unknownCmd))
    }
}
