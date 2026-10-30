package com.architect.driveaggregator.telemetry

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.nio.charset.Charset
import java.util.Locale

/**
 * OkHttp Interceptor that captures HTTP request and response metadata
 * (method, URL, headers, status code, error messages) while ensuring data privacy
 * through robust redaction of sensitive headers and payload fields (password, token, etc.).
 * Logs the captured telemetry data to AgentLogger.
 */
class TelemetryInterceptor : Interceptor {

    companion object {
        private val SENSITIVE_KEYS = setOf(
            "password", "token", "secret", "api_key", "client_id", "client_secret",
            "authorization", "credential", "bearer", "key", "access_token", "refresh_token"
        )

        private val SENSITIVE_HEADERS = setOf(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key"
        )

        private val UTF8 = Charset.forName("UTF-8")

        fun redactHeaderValue(name: String, value: String): String {
            val lowerName = name.lowercase(Locale.ROOT)
            if (SENSITIVE_HEADERS.any { lowerName.contains(it) }) {
                return "[REDACTED]"
            }
            return value
        }

        fun redactText(text: String?): String {
            if (text.isNullOrBlank()) return text ?: ""
            // Redact key-value patterns like password="xyz", token='xyz', or client_secret: xyz
            val pattern = Regex("(?i)(password|token|secret|api_key|client_id|client_secret|authorization|credential|bearer|key)\\s*[:=]\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s,}\\)]+))")
            return pattern.replace(text) { matchResult ->
                val key = matchResult.groupValues[1]
                val dQuote = matchResult.groupValues[2]
                val sQuote = matchResult.groupValues[3]
                when {
                    dQuote.isNotEmpty() -> "$key=\"[REDACTED]\""
                    sQuote.isNotEmpty() -> "$key='[REDACTED]'"
                    else -> "$key=[REDACTED]"
                }
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val url = request.url.toString()
        val method = request.method

        // Capture and redact request headers
        val requestHeaders = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            val value = request.header(name) ?: ""
            requestHeaders[name] = redactHeaderValue(name, value)
        }

        // Capture request body if present and text/json
        var requestBodyStr: String? = null
        try {
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                val contentType = body.contentType()
                val charset = contentType?.charset(UTF8) ?: UTF8
                val bodyString = buffer.readString(charset)
                if (bodyString.isNotBlank()) {
                    requestBodyStr = redactText(bodyString)
                }
            }
        } catch (_: Exception) {
            requestBodyStr = "[Unable to read request body]"
        }

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            AgentLogger.logEvent(
                level = "ERROR",
                category = "NETWORK",
                event = "HTTP_ERROR",
                message = "HTTP $method $url failed after ${duration}ms: ${e.message}",
                payload = mapOf(
                    "method" to method,
                    "url" to url,
                    "duration_ms" to duration,
                    "error" to (e.message ?: "Unknown error")
                ),
                stack = e.stackTraceToString()
            )
            throw e
        }

        val duration = System.currentTimeMillis() - startTime
        val code = response.code
        val isSuccessful = response.isSuccessful

        // Capture and redact response headers
        val responseHeaders = mutableMapOf<String, String>()
        for (name in response.headers.names()) {
            val value = response.header(name) ?: ""
            responseHeaders[name] = redactHeaderValue(name, value)
        }

        // Capture response body if safe to read
        var responseBodyStr: String? = null
        try {
            val responseBody = response.body
            if (responseBody != null) {
                val source = responseBody.source()
                source.request(Long.MAX_VALUE) // Buffer the entire body
                val buffer = source.buffer.clone()
                val contentType = responseBody.contentType()
                val charset = contentType?.charset(UTF8) ?: UTF8
                val bodyString = buffer.readString(charset)
                if (bodyString.isNotBlank()) {
                    responseBodyStr = redactText(bodyString)
                }

                // Recreate response body so downstream can still read it
                val wrappedBody = bodyString.toResponseBody(contentType)
                response = response.newBuilder().body(wrappedBody).build()
            }
        } catch (_: Exception) {
            responseBodyStr = "[Unable to read response body]"
        }

        val level = if (isSuccessful) "INFO" else "WARN"
        AgentLogger.logEvent(
            level = level,
            category = "NETWORK",
            event = "HTTP_TRANSACTION",
            message = "HTTP $method $url -> $code (${duration}ms)",
            payload = mapOf(
                "method" to method,
                "url" to url,
                "status_code" to code,
                "duration_ms" to duration,
                "request_headers" to requestHeaders,
                "request_body" to requestBodyStr,
                "response_headers" to responseHeaders,
                "response_body" to responseBodyStr
            )
        )

        return response
    }
}
