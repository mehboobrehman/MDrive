package com.architect.driveaggregator.util

import android.view.MotionEvent
import com.architect.driveaggregator.telemetry.AgentLogger

class ActivityTouchInterceptor(private val activityName: String) {
    private var startX = 0f
    private var startY = 0f
    private var startXTime = 0L
    private var lastMoveLogTime = 0L

    fun handleTouchEvent(ev: MotionEvent) {
        val now = System.currentTimeMillis()
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                startXTime = now
                lastMoveLogTime = 0L
            }
            MotionEvent.ACTION_MOVE -> {
                if (now - lastMoveLogTime >= 100) {
                    lastMoveLogTime = now
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    val gestureType = if (Math.abs(dx) > Math.abs(dy)) "TOUCH_SWIPE" else "TOUCH_DRAG"
                    AgentLogger.logEvent(
                        level = "DEBUG",
                        category = "GESTURE",
                        event = gestureType,
                        message = "Touch move intercepted on $activityName",
                        payload = mapOf(
                            "x" to ev.x,
                            "y" to ev.y,
                            "dx" to dx,
                            "dy" to dy,
                            "duration_ms" to (now - startXTime)
                        )
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                val duration = now - startXTime
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

                val gestureType = if (duration < 200 && distance < 20) {
                    "TOUCH_TAP"
                } else if (Math.abs(dx) > Math.abs(dy)) {
                    "TOUCH_SWIPE"
                } else {
                    "TOUCH_SCROLL"
                }

                AgentLogger.logEvent(
                    level = "INFO",
                    category = "GESTURE",
                    event = gestureType,
                    message = "Gesture complete on $activityName",
                    payload = mapOf(
                        "x" to ev.x,
                        "y" to ev.y,
                        "dx" to dx,
                        "dy" to dy,
                        "distance" to distance,
                        "duration_ms" to duration
                    )
                )
            }
        }
    }
}