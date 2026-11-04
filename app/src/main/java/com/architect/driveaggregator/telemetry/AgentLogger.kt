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
import org.json.JSONArray
import kotlinx.coroutines.delay
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

typealias ConnectionState = com.architect.driveaggregator.telemetry.ConnectionStateManager.ConnectionState

object AgentLogger {
    private const val TAG = "AgentLogger"
    private const val DEFAULT_PORT = 8080
    private const val PREFS_NAME = "mdrive_telemetry"
    private const val PREF_KEY_SERVER_IP = "server_ip"
    private const val MAX_LOCAL_LOGS = 10000

    private val connectionStateManager = ConnectionStateManager(
        onStateChanged = { state ->
            when (state) {
                ConnectionState.CONNECTED -> logEvent("INFO", "NETWORK", "CONNECTION_ESTABLISHED", "Telemetry connection established")
                ConnectionState.DEGRADED -> logEvent("WARN", "NETWORK", "CONNECTION_DEGRADED", "Telemetry connection degraded")
                ConnectionState.OFFLINE -> logEvent("ERROR", "NETWORK", "CONNECTION_OFFLINE", "Telemetry connection offline")
                ConnectionState.CONNECTING -> logEvent("INFO", "NETWORK", "CONNECTION_RECONNECTING", "Attempting reconnection...")
                ConnectionState.DISCONNECTED -> logEvent("INFO", "NETWORK", "CONNECTION_DISCONNECTED", "Telemetry disconnected")
            }
        },
        onReconnectAttempt = {
            val ctx = appContext ?: return@ConnectionStateManager false
            val ip = discoverServerIp(ctx)
            if (!ip.isNullOrBlank() && pingConfigEndpoint(ip)) {
                cachedServerUrl = "http://$ip:$DEFAULT_PORT"
                performHandshake(cachedServerUrl!!)
                syncBufferedLogs()
                true
            } else {
                false
            }
        }
    )

    fun getConnectionState(): ConnectionState = connectionStateManager.getState()

    @Volatile
    private var droppedEventsCount = 0
    fun getDroppedEventsCount(): Int = droppedEventsCount

    private val sessionId = java.util.UUID.randomUUID().toString()
    fun getSessionId(): String = sessionId

    @Volatile
    private var clockOffsetMs = 0L
    fun getClockOffsetMs(): Long = clockOffsetMs

    private var sequenceNumber = 0L

    private val categorySamplingRates = mutableMapOf<String, Double>()
    fun setSamplingRate(category: String, rate: Double) {
        categorySamplingRates[category.uppercase()] = rate.coerceIn(0.0, 1.0)
    }
    private fun shouldSample(category: String): Boolean {
        val rate = categorySamplingRates[category.uppercase()] ?: 1.0
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        return Math.random() < rate
    }

    private val allowedCommands = setOf("set_log_level", "toggle_category", "set_sampling_rate", "request_snapshot", "set_force_401", "clear_logs", "trigger_sync")
    fun handleRemoteCommand(commandJson: String): Boolean {
        return try {
            var action = "unknown"
            var paramsLevel = "INFO"
            var paramsCategory = "ALL"
            var paramsEnabled = true
            var paramsRate = 1.0

            try {
                val json = JSONObject(commandJson)
                action = json.optString("action")
                val params = json.optJSONObject("params") ?: JSONObject()
                paramsLevel = params.optString("level", "INFO")
                paramsCategory = params.optString("category", "ALL")
                paramsEnabled = params.optBoolean("enabled", true)
                paramsRate = params.optDouble("rate", 1.0)
            } catch (_: Throwable) {
                // Fallback for Android stub runtime where org.json.JSONObject throws RuntimeException("Stub!")
                action = if (commandJson.contains("set_log_level")) "set_log_level"
                else if (commandJson.contains("toggle_category")) "toggle_category"
                else if (commandJson.contains("set_sampling_rate")) "set_sampling_rate"
                else if (commandJson.contains("request_snapshot")) "request_snapshot"
                else if (commandJson.contains("set_force_401")) "set_force_401"
                else if (commandJson.contains("clear_logs")) "clear_logs"
                else if (commandJson.contains("trigger_sync")) "trigger_sync"
                else "unknown"
                paramsLevel = "INFO"
                paramsCategory = "ALL"
                paramsEnabled = true
                paramsRate = 1.0
            }

            if (!allowedCommands.contains(action)) {
                logEvent("WARN", "CONFIG", "UNKNOWN_COMMAND", "Rejected unknown remote command: $action")
                return false
            }

            when (action) {
                "set_log_level" -> {
                    logEvent("INFO", "CONFIG", "CMD_SET_LOG_LEVEL", "Log level updated to $paramsLevel")
                }
                "toggle_category" -> {
                    logEvent("INFO", "CONFIG", "CMD_TOGGLE_CATEGORY", "Category $paramsCategory enabled=$paramsEnabled")
                }
                "set_sampling_rate" -> {
                    setSamplingRate(paramsCategory ?: "ALL", paramsRate ?: 1.0)
                    logEvent("INFO", "CONFIG", "CMD_SET_SAMPLING", "Sampling rate for $paramsCategory set to $paramsRate")
                }
                "request_snapshot" -> {
                    appContext?.let { logStartupStats(it) }
                    logEvent("INFO", "CONFIG", "CMD_SNAPSHOT", "Manual snapshot requested by remote controller")
                }
                "set_force_401" -> {
                    force401 = paramsEnabled
                    logEvent("INFO", "CONFIG", "CMD_SET_FORCE_401", "force401 set to $force401 via remote command")
                }
                "clear_logs" -> {
                    scope.launch {
                        try {
                            getOrResolveDao()?.clearAll()
                        } catch (_: Exception) {}
                    }
                    logEvent("INFO", "CONFIG", "CMD_CLEAR_LOGS", "Local logs cleared via remote command")
                }
                "trigger_sync" -> {
                    syncBufferedLogs()
                    logEvent("INFO", "CONFIG", "CMD_TRIGGER_SYNC", "Log sync triggered via remote command")
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            logEvent("ERROR", "CONFIG", "CMD_ERROR", "Failed to parse/execute remote command: ${e.message}")
            false
        }
    }

    private fun getMonotonicTimestamp(): Long {
        return try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    data class TelemetryEnvelope(
        val schemaVersion: String = "1.0",
        val sessionId: String,
        val deviceId: String,
        val sequenceNumber: Long,
        val monotonicTimestamp: Long,
        val wallClockTimestamp: String,
        val messageType: String,
        val payload: TelemetryPayload
    )

    data class TelemetryPayload(
        val level: String,
        val category: String,
        val event: String,
        val message: String,
        val payload: Map<String, Any?>?,
        val stack: String?
    )

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

    @Volatile
    var force401: Boolean = false
        private set

    @Volatile
    var featureFlags: Map<String, Boolean> = emptyMap()
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logQueue = ConcurrentLinkedQueue<TelemetryEnvelope>()

    private fun getDeviceId(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var deviceId = prefs.getString("device_id", null)
            if (deviceId.isNullOrBlank()) {
                deviceId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("device_id", deviceId).apply()
            }
            deviceId
        } catch (_: Exception) {
            "fallback_device_id"
        }
    }

    private suspend fun performHandshake(baseUrl: String) {
        val ctx = appContext ?: return
        val deviceId = getDeviceId(ctx)
        val handshakeJson = JSONObject().apply {
            put("deviceId", deviceId)
            put("sessionId", sessionId)
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("osVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("appVersion", try { BuildConfig.SEMVER_VERSION } catch (_: Exception) { "1.0.0" })
            put("buildId", try { BuildConfig.GIT_COMMIT_HASH } catch (_: Exception) { "unknown" })
            put("capabilities", JSONArray(listOf("logging", "metrics", "commands", "snapshot", "redaction")))
            put("deviceTimestamp", System.currentTimeMillis())
        }

        val requestTime = System.currentTimeMillis()
        try {
            val body = handshakeJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/handshake").post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseTime = System.currentTimeMillis()
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrBlank()) {
                        val resJson = JSONObject(responseBody)
                        val serverTime = resJson.optLong("serverTimestamp", 0L)
                        if (serverTime > 0L) {
                            val rtt = responseTime - requestTime
                            val estimatedServerTimeAtReceive = serverTime + (rtt / 2)
                            clockOffsetMs = estimatedServerTimeAtReceive - responseTime
                        }
                    }
                    logEvent("INFO", "STARTUP", "HANDSHAKE_SUCCESS", "Handshake completed successfully with host")
                }
            }
        } catch (e: Exception) {
            logEvent("WARN", "STARTUP", "HANDSHAKE_FAILED", "Handshake failed: ${e.message}")
        }
    }

    private fun isTestEnvironment(): Boolean {
        val override = System.getProperty("agentlogger.posting")
        if (override == "false") return true
        val fingerprint = Build.FINGERPRINT ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        val device = Build.DEVICE ?: ""
        return fingerprint.contains("robolectric", ignoreCase = true) ||
            manufacturer.contains("robolectric", ignoreCase = true) ||
            model.contains("robolectric", ignoreCase = true) ||
            device.contains("robolectric", ignoreCase = true) ||
            appContext == null ||
            try {
                Class.forName("org.robolectric.Robolectric")
                true
            } catch (_: Throwable) {
                false
            }
    }

    init {
        if (BuildConfig.DEBUG) {
            setupGlobalErrorHandling()
        }
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
            } catch (_: Exception) {}
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
        if (isTestEnvironment()) return
        scope.launch {
            performHandshake(cachedServerUrl!!)
            flushBuffer()
            syncBufferedLogs()
        }
        connectionStateManager.updateState(ConnectionState.CONNECTED)
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

    fun resetForTesting() {
        cachedServerUrl = null
        appContext = null
        logDao = null
        logQueue.clear()
        connectionStateManager.reset()
        droppedEventsCount = 0
        sequenceNumber = 0L
        clockOffsetMs = 0L
        categorySamplingRates.clear()
        scope.coroutineContext.cancelChildren()
    }

    fun initDynamicConfig(context: Context) {
        appContext = context.applicationContext
        try {
            logDao = AppDatabase.getInstance(context).logDao()
        } catch (_: Exception) {}

        try {
            (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(TelemetryActivityLifecycleCallbacks())
        } catch (_: Exception) {}

        if (!BuildConfig.DEBUG) return
        if (isTestEnvironment()) return

        startAnrWatchdog(context)
        startPerformanceSampling(context)

        scope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedIp = prefs.getString(PREF_KEY_SERVER_IP, null)
            if (!savedIp.isNullOrBlank()) {
                cachedServerUrl = "http://$savedIp:$DEFAULT_PORT"
                performHandshake(cachedServerUrl!!)
                flushBuffer()
                syncBufferedLogs()
                connectionStateManager.updateState(ConnectionState.CONNECTED)
            }

            val resolvedIp = discoverServerIp(context)
            if (resolvedIp != null) {
                cachedServerUrl = "http://$resolvedIp:$DEFAULT_PORT"
                prefs.edit().putString(PREF_KEY_SERVER_IP, resolvedIp).apply()
                performHandshake(cachedServerUrl!!)
                logEvent("INFO", "STARTUP", "DISCOVER_SUCCESS", "Discovered telemetry server at $cachedServerUrl")
                connectionStateManager.updateState(ConnectionState.CONNECTED)
            } else if (cachedServerUrl == null) {
                cachedServerUrl = "http://10.0.2.2:$DEFAULT_PORT"
                connectionStateManager.start()
            }

            flushBuffer()
            syncBufferedLogs()
            logStartupStats(context)
            startConfigPolling()
        }
    }

    private fun startAnrWatchdog(context: Context) {
        scope.launch {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            while (true) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var responderThread: Thread? = null
                mainHandler.post {
                    responderThread = Thread.currentThread()
                    latch.countDown()
                }
                val responded = latch.await(3000, TimeUnit.MILLISECONDS)
                if (!responded) {
                    val stackTrace = responderThread?.stackTrace?.joinToString("\n") { "  at $it" } ?: "No stack trace available"
                    logEvent(
                        level = "ERROR",
                        category = "DIAGNOSTIC",
                        event = "ANR_DETECTED",
                        message = "Main thread hang / ANR detected: unresponsive for > 3000ms",
                        payload = mapOf("thread_name" to (responderThread?.name ?: "main")),
                        stack = stackTrace
                    )
                }
                delay(10_000)
            }
        }
    }

    private fun startPerformanceSampling(context: Context) {
        scope.launch {
            while (true) {
                delay(60_000)
                try {
                    val runtime = Runtime.getRuntime()
                    val usedMem = runtime.totalMemory() - runtime.freeMemory()
                    val maxMem = runtime.maxMemory()
                    val activeThreads = Thread.activeCount()
                    val pid = android.os.Process.myPid()

                    logEvent(
                        level = "INFO",
                        category = "METRIC",
                        event = "PERF_SAMPLE",
                        message = "Periodic performance sample",
                        payload = mapOf(
                            "pid" to pid,
                            "used_memory_bytes" to usedMem,
                            "max_memory_bytes" to maxMem,
                            "active_threads" to activeThreads
                        )
                    )
                } catch (_: Exception) {}
            }
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

    private suspend fun fetchRemoteConfig() {
        val baseUrl = cachedServerUrl ?: return
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/config")
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val flags = mutableMapOf<String, Boolean>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key == "force_401") {
                                force401 = json.optBoolean("force_401", false)
                            } else {
                                flags[key] = json.optBoolean(key, false)
                            }
                        }
                        featureFlags = flags
                        logEvent("INFO", "CONFIG", "REMOTE_CONFIG_UPDATED", "Config updated: force_401=$force401, flags=$flags")
                    }
                }
            }
        } catch (e: Exception) {
            logEvent("ERROR", "CONFIG", "REMOTE_CONFIG_FAILED", "Failed to fetch remote config: ${e.message}")
        }
    }

    private fun startConfigPolling() {
        scope.launch {
            while (true) {
                fetchRemoteConfig()
                delay(60_000)
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
        val savedIp = prefs.getString(PREF_KEY_SERVER_IP, null)
        if (!savedIp.isNullOrBlank() && pingConfigEndpoint(savedIp)) {
            return savedIp
        }

        val gatewayIp = getWifiGatewayIp(context)
        if (!gatewayIp.isNullOrBlank() && pingConfigEndpoint(gatewayIp)) {
            prefs.edit().putString(PREF_KEY_SERVER_IP, gatewayIp).apply()
            return gatewayIp
        }

        val devHostIp = BuildConfig.DEV_HOST_IP
        if (!devHostIp.isNullOrBlank() && devHostIp != "127.0.0.1" && pingConfigEndpoint(devHostIp)) {
            prefs.edit().putString(PREF_KEY_SERVER_IP, devHostIp).apply()
            return devHostIp
        }

        if (pingConfigEndpoint("10.0.2.2")) {
            prefs.edit().putString(PREF_KEY_SERVER_IP, "10.0.2.2").apply()
            return "10.0.2.2"
        }

        val subnetPrefixes = getLocalSubnetPrefixes()
        for (prefix in subnetPrefixes) {
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
                val envelope = logQueue.poll() ?: break
                sendWithBackoff(baseUrl, envelope)
            }
        }
    }

    private fun sendEnvelopeHttp(baseUrl: String, envelope: TelemetryEnvelope): Boolean {
        return try {
            val json = JSONObject().apply {
                put("schemaVersion", envelope.schemaVersion)
                put("sessionId", envelope.sessionId)
                put("deviceId", envelope.deviceId)
                put("sequenceNumber", envelope.sequenceNumber)
                put("monotonicTimestamp", envelope.monotonicTimestamp)
                put("wallClockTimestamp", envelope.wallClockTimestamp)
                put("messageType", envelope.messageType)
                put("payload", JSONObject().apply {
                    put("level", envelope.payload.level)
                    put("category", envelope.payload.category)
                    put("event", envelope.payload.event)
                    put("message", envelope.payload.message)
                    put("payload", envelope.payload.payload?.let { JSONObject(it) } ?: JSONObject.NULL)
                    put("stack", envelope.payload.stack ?: JSONObject.NULL)
                })
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

    private suspend fun sendWithBackoff(baseUrl: String, envelope: TelemetryEnvelope): Boolean {
        connectionStateManager.updateState(ConnectionState.CONNECTING)
        var attempt = 0
        val maxAttempts = 5
        val baseDelayMs = 1000L
        val maxDelayMs = 30000L

        while (attempt < maxAttempts) {
            try {
                val success = sendEnvelopeHttp(baseUrl, envelope)
                if (success) {
                    connectionStateManager.updateState(ConnectionState.CONNECTED)
                    return true
                }
            } catch (_: Exception) {}

            val failures = connectionStateManager.getConsecutiveFailures() + 1
            if (failures >= 3) {
                connectionStateManager.updateState(ConnectionState.DEGRADED)
            }

            val exponential = minOf(maxDelayMs, baseDelayMs * (1L shl minOf(attempt, 6)))
            val jitter = (exponential * 0.2 * (Math.random() - 0.5)).toLong()
            val delayTime = maxOf(200L, exponential + jitter)
            delay(delayTime)
            attempt++
        }
        connectionStateManager.updateState(ConnectionState.OFFLINE)
        return false
    }

    /**
     * Synchronizes pending logs stored locally in Room database when connectivity is available.
     * Reads unsynced logs in chronological order (`isSynced = 0`, `ORDER BY timestamp ASC`),
     * pushes them to the telemetry server, and marks them synced.
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

                    val cat = logEntity.tag.substringBefore(':')
                    val evt = if (logEntity.tag.contains(':')) logEntity.tag.substringAfter(':') else "EVENT"
                    val seq = ++sequenceNumber
                    val deviceId = appContext?.let { getDeviceId(it) } ?: "test_device_id"
                    val messageType = when (cat.uppercase()) {
                        "NETWORK" -> "HTTP_TRANSACTION"
                        "STARTUP", "LIFECYCLE" -> "LIFECYCLE"
                        "METRIC" -> "METRIC"
                        else -> "LOG"
                    }

                    val envelope = TelemetryEnvelope(
                        schemaVersion = "1.0",
                        sessionId = sessionId,
                        deviceId = deviceId,
                        sequenceNumber = seq,
                        monotonicTimestamp = getMonotonicTimestamp(),
                        wallClockTimestamp = Instant.ofEpochMilli(logEntity.timestamp + clockOffsetMs).toString(),
                        messageType = messageType,
                        payload = TelemetryPayload(
                            level = logEntity.level,
                            category = cat,
                            event = evt,
                            message = logEntity.message,
                            payload = payloadMap,
                            stack = null
                        )
                    )

                    val success = sendWithBackoff(baseUrl, envelope)
                    if (success) {
                        successfullySyncedIds.add(logEntity.id)
                    } else {
                        break
                    }
                }

                if (successfullySyncedIds.isNotEmpty()) {
                    dao.markAsSynced(successfullySyncedIds)
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
        if (isTestEnvironment()) return
        if (!shouldSample(category)) return

        val redactedMessage = TelemetryRedactor.redactText(message)
        val redactedPayload = TelemetryRedactor.redactPayload(payload)

        val logMsg = "[$category:$event] $redactedMessage | Payload: ${redactedPayload ?: "{}"}"
        when (level.uppercase()) {
            "ERROR", "FATAL" -> Log.e(TAG, logMsg)
            "WARN" -> Log.w(TAG, logMsg)
            "DEBUG" -> Log.d(TAG, logMsg)
            else -> Log.i(TAG, logMsg)
        }

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

        scope.launch {
            try {
                val dao = getOrResolveDao()
                dao?.let {
                    it.insert(
                        LogEntity(
                            timestamp = timestampMillis,
                            level = level,
                            tag = tagStr,
                            message = redactedMessage,
                            metadata = metadataJson,
                            isSynced = false
                        )
                    )
                    // FIFO drop policy enforcement
                    val count = it.getLogCount()
                    if (count > MAX_LOCAL_LOGS) {
                        val overflow = count - MAX_LOCAL_LOGS
                        it.deleteOldestUnsynced(overflow)
                        droppedEventsCount += overflow
                    }
                }
            } catch (_: Exception) {}
        }

        val seq = ++sequenceNumber
        val deviceId = appContext?.let { getDeviceId(it) } ?: "test_device_id"
        val messageType = when (category.uppercase()) {
            "NETWORK" -> "HTTP_TRANSACTION"
            "STARTUP", "LIFECYCLE" -> "LIFECYCLE"
            "METRIC" -> "METRIC"
            else -> "LOG"
        }

        val envelope = TelemetryEnvelope(
            schemaVersion = "1.0",
            sessionId = sessionId,
            deviceId = deviceId,
            sequenceNumber = seq,
            monotonicTimestamp = android.os.SystemClock.elapsedRealtime(),
            wallClockTimestamp = Instant.ofEpochMilli(timestampMillis + clockOffsetMs).toString(),
            messageType = messageType,
            payload = TelemetryPayload(
                level = level,
                category = category,
                event = event,
                message = redactedMessage,
                payload = redactedPayload,
                stack = stack
            )
        )

        val currentUrl = cachedServerUrl
        if (currentUrl == null) {
            if (logQueue.size >= 1000) {
                logQueue.poll()
                droppedEventsCount++
            }
            logQueue.add(envelope)
        } else {
            scope.launch {
                while (true) {
                    val queued = logQueue.poll() ?: break
                    sendWithBackoff(currentUrl, queued)
                }
                sendWithBackoff(currentUrl, envelope)
            }
        }
    }
}
