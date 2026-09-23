package com.architect.driveaggregator.provider

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper utility for executing cloud provider API requests with retry logic, exponential backoff,
 * and handling 429 Too Many Requests with Retry-After headers.
 */
suspend fun <T> executeWithRetry(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000L,
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    var lastException: Throwable? = null

    for (attempt in 0..maxRetries) {
        try {
            return block()
        } catch (e: ProviderHttpException) {
            lastException = e
            if (e.statusCode == 429 || e.statusCode in 500..599) {
                if (attempt == maxRetries) throw e
                val retryAfterHeader = e.retryAfterSeconds
                val delayTime = if (retryAfterHeader > 0) {
                    retryAfterHeader * 1000L
                } else {
                    currentDelay
                }
                delay(delayTime)
                currentDelay *= 2
            } else {
                throw e
            }
        } catch (e: IOException) {
            lastException = e
            if (attempt == maxRetries) throw e
            delay(currentDelay)
            currentDelay *= 2
        }
    }
    throw lastException ?: IOException("Operation failed after $maxRetries retries")
}

class ProviderHttpException(
    val statusCode: Int,
    val responseMessage: String,
    val retryAfterSeconds: Long = 0L
) : IOException("HTTP $statusCode: $responseMessage")
