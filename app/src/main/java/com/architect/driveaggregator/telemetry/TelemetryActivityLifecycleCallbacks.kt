package com.architect.driveaggregator.telemetry

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Activity lifecycle callbacks to track screen/activity transitions and emit telemetry events.
 */
class TelemetryActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        AgentLogger.logEvent("INFO", "LIFECYCLE", "ACTIVITY_CREATED", "Activity created: ${activity.javaClass.simpleName}")
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        AgentLogger.logEvent("INFO", "LIFECYCLE", "ACTIVITY_RESUMED", "Activity resumed: ${activity.javaClass.simpleName}")
    }

    override fun onActivityPaused(activity: Activity) {
        AgentLogger.logEvent("INFO", "LIFECYCLE", "ACTIVITY_PAUSED", "Activity paused: ${activity.javaClass.simpleName}")
    }

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        AgentLogger.logEvent("INFO", "LIFECYCLE", "ACTIVITY_DESTROYED", "Activity destroyed: ${activity.javaClass.simpleName}")
    }
}
