package com.architect.driveaggregator.telemetry

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.architect.driveaggregator.BuildConfig
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.dao.LogDao
import com.architect.driveaggregator.data.entities.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

object AgentLogger {
    private const val TAG = "AgentLogger"
    private const val DEFAULT_PORT = 8080
    private const val PREFS_NAME = "mdrive_telemetry"
    private const val PREF_KEY_SERVER_IP = "server_ip"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dedicated HttpClient with 2-second timeout for normal telemetry logging
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            try {
                requestBuilder.header("X-App-Version", BuildConfig.SEMVER_VERSION)
                requestBuilder.header("X-Build-Id", BuildConfig.GIT_COMMIT_HASH)
            } catch (_: Exception) {}
            chain.proceed(requestBuilder.build())
        }
        .build()

    // Fast-failing HttpClient with 350ms timeout for concurrent LAN host discovery
    private val pingHttpClient = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(350, TimeUnit.MILLISECONDS)
        .writeTimeout(350, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .dispatcher(Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        })
        .build()

    @Volatile
    private var cachedServerUrl: String? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var logDao: LogDao? = null

    // Thread-safe FIFO queue to buffer log entries before dynamic endpoint discovery finishes
    private val logQueue = ConcurrentLinkedQueue<LogPayload>()

    private val SENSITIVE_KEYS = setOf(
        "password", "token", "secret", "api_key", "client_id", "client_secret",
        "authorization", "credential", "bearer", "key"
    )

    /**
     * Detects Robolectric / unit-test environment so telemetry is never POSTed during tests.
     * Checks Build.FINGERPRINT, Build.MANUFACTURER, and Build.MODEL for "robolectric",
     * plus an explicit opt-out via the system property "agentlogger.posting=false".
     */
    private fun isTestEnvironment(): Boolean {
        val override = System.getProperty("agentlogger.posting")
        if (override == "false") return true
        val fingerprint = Build.FINGERPRINT ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return fingerprint.contains("robolectric", ignoreCase = true) ||
            manufacturer.contains("robolectric", ignoreCase = true) ||
            model.contains("robolectric", ignoreCase = true)
    }

    private data class LogPayload(
        val level: String,
        val category: String,
        val event: String,
        val message: String,
        val payload: Map<String, Any?>?,
        val stack: String?,
        val timestamp: String = Instant.now().toString()
    )

    init {
        if (BuildConfig.DEBUG) {
            setupGlobalErrorHandling()
        }
    }

    private fun redactMessage(message: String): String {
        val pattern = Regex("(?i)(password|token|secret|api_key|client_id|client_secret|authorization|credential|bearer|key)\\s*[:=]\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s,}\\)]+))")
        return pattern.replace(message) { matchResult ->
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

    private fun redactPayload(map: Map<String, Any?>?): Map<String, Any?>? {
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
                            if (item is Map<*, *>) {
                                val typedItem = item.entries.associate { it.key.toString() to it.value }
                                redactPayload(typedItem)
                            } else {
                                item
                            }
                        }
                    }
                    else -> result[k] = v
                }
            }
        }
        return result
    }

    private fun setupGlobalErrorHandling() {
        if (!BuildConfig.DEBUG) return

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTraceStr = sw.toString()

                Log.e(TAG, "FATAL EXCEPTION in thread ${thread.name}", throwable)

                // Synchronous immediate dispatch for fatal crash
                runCatching {
                    val url = cachedServerUrl ?: "http://10.0.2.2:$DEFAULT_PORT"
                    val json = JSONObject().apply {
                        put("timestamp", Instant.now().toString())
                        put("level", "FATAL")
                        put("category", "DIAGNOSTIC")
                        put("event", "ERROR_CRASH")
                        put("message", "Uncaught exception in thread ${thread.name}: ${throwable.message}")
                        put("payload", JSONObject(mapOf("thread_name" to thread.name)))
                        put("stack", stackTraceStr)
                    }
                    val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url("$url/api/log").post(body).build()
                    httpClient.newCall(request).execute().close()
                }
            } catch (_: Exception) {
                // Ignore crash reporting failures
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun setServerAddress(ip: String, port: Int = DEFAULT_PORT, context: Context? = null) {
        cachedServerUrl = "http://$ip:$port"
        val targetContext = context?.applicationContext ?: appContext
        targetContext?.let { ctx ->
            appContext = ctx
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(PREF_KEY_SERVER_IP, ip).apply()
            } catch (_: Exception) {}
        }
        // In test environment keep cachedServerUrl (for getServerUrl assertions)
        // but do not flush/POST to the network
        if (isTestEnvironment()) return
        flushBuffer()
        syncBufferedLogs()
        logEvent("INFO", "STARTUP", "DISCOVER_SUCCESS", "Telemetry server set to http://$ip:$port")
    }

    fun getServerUrl(): String? = cachedServerUrl

    fun getServerIp(context: Context): String? {
        cachedServerUrl?.let { url ->
            try {
                val uri = java.net.URI(url)
                if (!uri.host.isNullOrBlank()) return uri.host
            } catch (_: Exception) {}
        }
        val targetContext = context.applicationContext ?: appContext
        return targetContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getString(PREF_KEY_SERVER_IP, null)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun isConfigured(): Boolean = cachedServerUrl != null

    /**
     * Resets AgentLogger state for unit testing purposes.
     * Clears cached URL, context, and log queue.
     */
    fun resetForTesting() {
        cachedServerUrl = null
        appContext = null
        logDao = null
        logQueue.clear()
        scope.coroutineContext.cancelChildren()
    }

    fun initDynamicConfig(context: Context) {
        appContext = context.applicationContext
        // Obtain LogDao from AppDatabase singleton if possible
        try {
            logDao = AppDatabase.getInstance(context).logDao()
        } catch (_: Exception) {}

        if (!BuildConfig.DEBUG) return

        // In test environment keep appContext (needed by tests) but skip all network activity
        if (isTestEnvironment()) return

        scope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedIp = prefs.getString(PREF_KEY_SERVER_IP, null)
            if (!savedIp.isNullOrBlank()) {
                cachedServerUrl = "http://$savedIp:$DEFAULT_PORT"
                flushBuffer()
                syncBufferedLogs()
            }

            val resolvedIp = discoverServerIp(context)
            if (resolvedIp != null) {
                cachedServerUrl = "http://$resolvedIp:$DEFAULT_PORT"
                prefs.edit().putString(PREF_KEY_SERVER_IP, resolvedIp).apply()
                logEvent("INFO", "STARTUP", "DISCOVER_SUCCESS", "Discovered telemetry server at $cachedServerUrl")
            } else if (cachedServerUrl == null) {
                // Fallback default for emulator (10.0.2.2)
                cachedServerUrl = "http://10.0.2.2:$DEFAULT_PORT"
            }

            flushBuffer()
            syncBufferedLogs()

            // Startup stats logging under STARTUP:APP_INIT
            logStartupStats(context)

            // Periodic metrics monitoring under DIAGNOSTIC:SYS_METRICS (every 5s)
            startSystemMetricsMonitoring()
        }
    }

    private fun logStartupStats(context: Context) {
        try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            
            val totalMem = memInfo.totalMem
            val availMem = memInfo.availMem
            val pid = android.os.Process.myPid()
            val androidRelease = android.os.Build.VERSION.RELEASE
            val sdkInt = android.os.Build.VERSION.SDK_INT
            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            val activeThreads = Thread.activeCount()
            val cpuArch = android.os.Build.SUPPORTED_ABIS.joinToString()

            logEvent(
                level = "INFO",
                category = "STARTUP",
                event = "APP_INIT",
                message = "App initialization stats",
                payload = mapOf(
                    "pid" to pid,
                    "android_version" to androidRelease,
                    "sdk_level" to sdkInt,
                    "manufacturer" to manufacturer,
                    "model" to model,
                    "active_thread_count" to activeThreads,
                    "cpu_architecture" to cpuArch,
                    "total_ram" to totalMem,
                    "available_ram" to availMem
                )
            )
        } catch (e: Exception) {
            logEvent("ERROR", "STARTUP", "APP_INIT_ERROR", "Failed to capture startup stats: ${e.message}")
        }
    }

    private fun startSystemMetricsMonitoring() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                try {
                    val runtime = Runtime.getRuntime()
                    val totalMem = runtime.totalMemory()
                    val freeMem = runtime.freeMemory()
                    val allocMem = totalMem - freeMem
                    val maxMem = runtime.maxMemory()
                    val threadCount = Thread.activeCount()

                    logEvent(
                        level = "DEBUG",
                        category = "DIAGNOSTIC",
                        event = "SYS_METRICS",
                        message = "Periodic system metrics",
                        payload = mapOf(
                            "allocated_memory" to allocMem,
                            "heap_size" to totalMem,
                            "max_memory" to maxMem,
                            "thread_count" to threadCount
                        )
                    )
                } catch (e: Exception) {
                    // Fail silently
                }
            }
        }
    }

    private fun getWifiGatewayIp(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            @Suppress("DEPRECATION")
            val dhcpInfo = wifiManager.dhcpInfo ?: return null
            val gateway = dhcpInfo.gateway
            if (gateway == 0) return null
            val ip = "%d.%d.%d.%d".format(
                gateway and 0xFF,
                (gateway shr 8) and 0xFF,
                (gateway shr 16) and 0xFF,
                (gateway shr 24) and 0xFF
            )
            if (ip == "0.0.0.0") null else ip
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun discoverServerIp(context: Context): String? {
        if (!BuildConfig.DEBUG) return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Try saved IP from SharedPreferences
        val savedIp = prefs.getString(PREF_KEY_SERVER_IP, null)
        if (!savedIp.isNullOrBlank() && pingConfigEndpoint(savedIp)) {
            return savedIp
        }

        // 2. Try Wi-Fi DHCP Gateway
        val gatewayIp = getWifiGatewayIp(context)
        if (!gatewayIp.isNullOrBlank() && pingConfigEndpoint(gatewayIp)) {
            prefs.edit().putString(PREF_KEY_SERVER_IP, gatewayIp).apply()
            return gatewayIp
        }

        // 3. Try DEV_HOST_IP from BuildConfig (injected during Gradle build)
        val devHostIp = BuildConfig.DEV_HOST_IP
        if (!devHostIp.isNullOrBlank() && devHostIp != "127.0.0.1" && pingConfigEndpoint(devHostIp)) {
            prefs.edit().putString(PREF_KEY_SERVER_IP, devHostIp).apply()
            return devHostIp
        }

        // 4. Try Android Emulator host gateway (10.0.2.2)
        if (pingConfigEndpoint("10.0.2.2")) {
            prefs.edit().putString(PREF_KEY_SERVER_IP, "10.0.2.2").apply()
            return "10.0.2.2"
        }

        // 5. Try Wi-Fi subnet scanning
        val subnetPrefixes = getLocalSubnetPrefixes()
        for (prefix in subnetPrefixes) {
            // Fast probe gateway candidates (.1, .2, .254, or device's gateway if in same prefix)
            val fastHosts = mutableListOf("1", "2", "254")
            if (gatewayIp != null && gatewayIp.startsWith(prefix)) {
                val lastOctet = gatewayIp.substring(prefix.length)
                if (lastOctet !in fastHosts) fastHosts.add(0, lastOctet)
            }
            for (gw in fastHosts) {
                val candidate = "$prefix$gw"
                if (pingConfigEndpoint(candidate)) {
                    prefs.edit().putString(PREF_KEY_SERVER_IP, candidate).apply()
                    return candidate
                }
            }

            // Probe entire /24 subnet concurrently
            val scanned = scanSubnet(prefix, gatewayIp)
            if (scanned != null) {
                prefs.edit().putString(PREF_KEY_SERVER_IP, scanned).apply()
                return scanned
            }
        }

        return null
    }

    private fun getLocalSubnetPrefixes(): List<String> {
        val prefixes = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (networkInterface in interfaces.asSequence()) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (address in networkInterface.inetAddresses.asSequence()) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress ?: continue
                        val lastDot = hostAddress.lastIndexOf('.')
                        if (lastDot > 0) {
                            prefixes.add(hostAddress.substring(0, lastDot + 1))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return prefixes.distinct()
    }

    private suspend fun scanSubnet(prefix: String, priorityIp: String? = null): String? = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext null
        val resultChannel = Channel<String>(capacity = 1)
        val jobs = mutableListOf<Job>()

        val highPriority = mutableListOf(1, 2, 254)
        if (priorityIp != null && priorityIp.startsWith(prefix)) {
            val lastOctet = priorityIp.substring(prefix.length).toIntOrNull()
            if (lastOctet != null && lastOctet in 1..254 && lastOctet !in highPriority) {
                highPriority.add(0, lastOctet)
            }
        }
        val devHost = BuildConfig.DEV_HOST_IP
        if (!devHost.isNullOrBlank() && devHost.startsWith(prefix)) {
            val lastOctet = devHost.substring(prefix.length).toIntOrNull()
            if (lastOctet != null && lastOctet in 1..254 && lastOctet !in highPriority) {
                highPriority.add(0, lastOctet)
            }
        }
        val remaining = (1..254).filter { it !in highPriority }
        val probeOrder = highPriority + remaining

        for (host in probeOrder) {
            val candidateIp = "$prefix$host"
            val job = launch {
                if (pingConfigEndpoint(candidateIp)) {
                    resultChannel.trySend(candidateIp)
                }
            }
            jobs.add(job)
        }

        val discovered = withTimeoutOrNull(5000) {
            resultChannel.receiveCatching().getOrNull()
        }

        jobs.forEach { it.cancel() }
        discovered
    }

    private suspend fun pingConfigEndpoint(ip: String): Boolean = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext false
        try {
            val request = Request.Builder()
                .url("http://$ip:$DEFAULT_PORT/api/config")
                .get()
                .build()
            pingHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun flushBuffer() {
        val baseUrl = cachedServerUrl ?: return
        scope.launch {
            while (true) {
                val entry = logQueue.poll() ?: break
                sendHttpLog(baseUrl, entry)
            }
        }
    }

    private fun sendHttpLog(baseUrl: String, entry: LogPayload): Boolean {
        return try {
            val json = JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("level", entry.level)
                put("category", entry.category)
                put("event", entry.event)
                put("message", entry.message)
                if (entry.payload != null) {
                    put("payload", JSONObject(entry.payload))
                } else {
                    put("payload", JSONObject())
                }
                put("stack", entry.stack ?: JSONObject.NULL)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/log")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Synchronizes pending logs stored locally in Room database when connectivity is available.
     * Reads unsynced logs (`isSynced = 0`), pushes them to the telemetry server, and marks them synced.
     */
    fun syncBufferedLogs() {
        val baseUrl = cachedServerUrl ?: return
        if (isTestEnvironment()) return

        scope.launch {
            val dao = getOrResolveDao() ?: return@launch
            try {
                val unsynced = dao.getUnsyncedLogs()
                if (unsynced.isEmpty()) return@launch

                val successfullySyncedIds = mutableListOf<Long>()
                for (logEntity in unsynced) {
                    // Reconstruct payload map from metadata JSON if present
                    val payloadMap = try {
                        if (logEntity.metadata.isNotBlank() && logEntity.metadata != "{}") {
                            val jsonObj = JSONObject(logEntity.metadata)
                            jsonObj.keys().asSequence().associateWith { jsonObj.get(it) }
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }

                    val payload = LogPayload(
                        level = logEntity.level,
                        category = logEntity.tag.substringBefore(':'),
                        event = if (logEntity.tag.contains(':')) logEntity.tag.substringAfter(':') else "EVENT",
                        message = logEntity.message,
                        payload = payloadMap,
                        stack = null,
                        timestamp = Instant.ofEpochMilli(logEntity.timestamp).toString()
                    )

                    val success = sendHttpLog(baseUrl, payload)
                    if (success) {
                        successfullySyncedIds.add(logEntity.id)
                    } else {
                        // Stop trying if server is currently unreachable
                        break
                    }
                }

                if (successfullySyncedIds.isNotEmpty()) {
                    dao.markAsSynced(successfullySyncedIds)
                    // Cleanup old successfully synced logs older than 7 days
                    val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                    dao.deleteSyncedLogs(sevenDaysAgo)
                }
            } catch (_: Exception) {}
        }
    }

    private fun getOrResolveDao(): LogDao? {
        if (logDao != null) return logDao
        val ctx = appContext ?: return null
        return try {
            val dao = AppDatabase.getInstance(ctx).logDao()
            logDao = dao
            dao
        } catch (_: Exception) {
            null
        }
    }

    fun logToAgent(level: String, message: String, stack: String? = null) {
        logEvent(level, "EXECUTION", "GENERIC_LOG", message, null, stack)
    }

    fun logEvent(
        level: String,
        category: String,
        event: String,
        message: String,
        payload: Map<String, Any?>? = null,
        stack: String? = null
    ) {
        if (!BuildConfig.DEBUG) return

        val redactedMessage = redactMessage(message)
        val redactedPayload = redactPayload(payload)

        // Mirror immediately to Android Logcat
        val logMsg = "[$category:$event] $redactedMessage | Payload: ${redactedPayload ?: "{}"}"
        when (level.uppercase()) {
            "ERROR", "FATAL" -> Log.e(TAG, logMsg)
            "WARN" -> Log.w(TAG, logMsg)
            "DEBUG" -> Log.d(TAG, logMsg)
            else -> Log.i(TAG, logMsg)
        }

        // In test environment, only mirror to Logcat — do not queue or POST to network / Room DB
        if (isTestEnvironment()) return

        val timestampMillis = System.currentTimeMillis()
        val metadataJson = if (redactedPayload != null && redactedPayload.isNotEmpty()) {
            try {
                JSONObject(redactedPayload).toString()
            } catch (_: Exception) {
                "{}"
            }
        } else {
            "{}"
        }

        val tagStr = "$category:$event"

        // 1. Persist locally in Room database via LogDao
        scope.launch {
            try {
                val dao = getOrResolveDao()
                dao?.insert(
                    LogEntity(
                        timestamp = timestampMillis,
                        level = level,
                        tag = tagStr,
                        message = redactedMessage,
                        metadata = metadataJson,
                        isSynced = false
                    )
                )
            } catch (_: Exception) {}
        }

        val entry = LogPayload(
            level = level,
            category = category,
            event = event,
            message = redactedMessage,
            payload = redactedPayload,
            stack = stack,
            timestamp = Instant.ofEpochMilli(timestampMillis).toString()
        )

        val currentUrl = cachedServerUrl
        if (currentUrl == null) {
            logQueue.add(entry)
        } else {
            scope.launch {
                // Drain any queued entries in FIFO order
                while (true) {
                    val queued = logQueue.poll() ?: break
                    sendHttpLog(currentUrl, queued)
                }
                val success = sendHttpLog(currentUrl, entry)
                if (success) {
                    // Mark recently inserted entry as synced if needed, or rely on batch syncBufferedLogs()
                }
            }
        }
    }
}
