package com.architect.driveaggregator.telemetry

import java.util.Locale

/**
 * Handles payload and message redaction for sensitive data:
 * credentials, tokens, cookies, auth headers, emails, phone numbers.
 */
object TelemetryRedactor {
    private val SENSITIVE_KEYS = setOf(
        "password", "token", "secret", "api_key", "client_id", "client_secret",
        "authorization", "credential", "bearer", "key", "access_token", "refresh_token",
        "passwd", "pwd", "auth"
    )

    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key"
    )

    fun redactHeaderValue(name: String, value: String): String {
        val lowerName = name.lowercase(Locale.ROOT)
        if (SENSITIVE_HEADERS.any { lowerName.contains(it) }) {
            return "[REDACTED]"
        }
        return value
    }

    fun redactText(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""
        
        // 1. Redact key-value pairs (e.g. password="foo", token='bar', secret: baz)
        val kvPattern = Regex("(?i)(password|token|secret|api_key|client_id|client_secret|authorization|credential|bearer|key|pwd)\\s*[:=]\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s,}\\)]+))")
        var redacted = kvPattern.replace(text) { matchResult ->
            val key = matchResult.groupValues[1]
            val dQuote = matchResult.groupValues[2]
            val sQuote = matchResult.groupValues[3]
            when {
                dQuote.isNotEmpty() -> "$key=\"[REDACTED]\""
                sQuote.isNotEmpty() -> "$key='[REDACTED]'"
                else -> "$key=[REDACTED]"
            }
        }

        // 2. Redact email addresses
        val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        redacted = emailPattern.replace(redacted, "[REDACTED_EMAIL]")

        // 3. Redact phone numbers (10 to 15 digits, optional + prefix)
        val phonePattern = Regex("\\+?[0-9]{10,15}")
        redacted = phonePattern.replace(redacted) { match ->
            val str = match.value
            if (str.length >= 10) "[REDACTED_PHONE]" else str
        }

        return redacted
    }

    fun redactPayload(map: Map<String, Any?>?): Map<String, Any?>? {
        if (map == null) return null
        val result = mutableMapOf<String, Any?>()
        for ((k, v) in map) {
            val keyLower = k.lowercase()
            val isSensitive = SENSITIVE_KEYS.any { keyLower.contains(it) }
            if (isSensitive) {
                result[k] = "[REDACTED]"
            } else {
                when (v) {
                    is Map<*, *> -> {
                        val typedMap = v.entries.associate { it.key.toString() to it.value }
                        result[k] = redactPayload(typedMap)
                    }
                    is List<*> -> {
                        result[k] = v.map { item ->
                            when (item) {
                                is Map<*, *> -> {
                                    val typedItem = item.entries.associate { it.key.toString() to it.value }
                                    redactPayload(typedItem)
                                }
                                is String -> redactText(item)
                                else -> item
                            }
                        }
                    }
                    is String -> {
                        result[k] = redactText(v)
                    }
                    else -> result[k] = v
                }
            }
        }
        return result
    }
}
