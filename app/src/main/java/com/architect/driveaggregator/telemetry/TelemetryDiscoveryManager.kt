package com.architect.driveaggregator.telemetry

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class TelemetryDiscoveryManager(
    private val context: Context,
    private val onDiscoverySuccess: (String, Int) -> Unit,
    private val onDiscoveryFailed: () -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        stopDiscovery()
        onDiscoveryFailed()
        showToast("Telemetry server discovery timed out.")
    }

    private val SERVICE_TYPE = "_mdrive-telemetry._tcp."

    init {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                // Discovery started
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            handler.removeCallbacks(timeoutRunnable)
                            onDiscoverySuccess(resolvedServiceInfo.host.hostAddress ?: "", resolvedServiceInfo.port)
                            stopDiscovery()
                        }

                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            // Ignore for now
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.removeCallbacks(timeoutRunnable)
                onDiscoveryFailed()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
    }

    fun startDiscovery() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 10000)
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        handler.removeCallbacks(timeoutRunnable)
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
