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
}
