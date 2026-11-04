package com.architect.driveaggregator.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryRedactorTest {

    @Test
    fun testRedactPassword() {
        val original = "Login failed with password=\"secret123\" or pwd='mysecret' and api_key=xyz123"
        val redacted = TelemetryRedactor.redactText(original)
        assertEquals("Login failed with password=\"[REDACTED]\" or pwd='[REDACTED]' and api_key=[REDACTED]", redacted)
    }

    @Test
    fun testRedactEmailAndPhone() {
        val original = "User test@example.com contacted support at +12345678901 and alt 0987654321."
        val redacted = TelemetryRedactor.redactText(original)
        assertEquals("User [REDACTED_EMAIL] contacted support at [REDACTED_PHONE] and alt [REDACTED_PHONE].", redacted)
    }

    @Test
    fun testRedactPayload() {
        val payload = mapOf(
            "username" to "john_doe",
            "password" to "supersecret",
            "email" to "john@example.com",
            "nested" to mapOf(
                "token" to "bearer_token_abc"
            )
        )
        val redacted = TelemetryRedactor.redactPayload(payload)
        assertEquals("john_doe", redacted?.get("username"))
        assertEquals("[REDACTED]", redacted?.get("password"))
        assertEquals("[REDACTED_EMAIL]", redacted?.get("email")) // email inside string / text or payload
        val nested = redacted?.get("nested") as? Map<*, *>
        assertEquals("[REDACTED]", nested?.get("token"))
    }

    @Test
    fun testRedactHeaderValue() {
        val authHeader = TelemetryRedactor.redactHeaderValue("Authorization", "Bearer secret_token_xyz")
        assertEquals("[REDACTED]", authHeader)

        val normalHeader = TelemetryRedactor.redactHeaderValue("Content-Type", "application/json")
        assertEquals("application/json", normalHeader)
    }
}
