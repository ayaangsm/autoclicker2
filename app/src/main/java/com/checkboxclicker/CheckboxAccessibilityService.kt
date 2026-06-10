package com.checkboxclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class CheckboxAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_CLICK_ALL = "com.checkboxclicker.CLICK_ALL"
        const val ACTION_STATUS = "com.checkboxclicker.STATUS"
        const val EXTRA_COORDS = "coords"
        var instance: CheckboxAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CLICK_ALL) {
                val coords = intent.getFloatArrayExtra(EXTRA_COORDS) ?: return
                fireAllTaps(coords)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerReceiver(receiver, IntentFilter(ACTION_CLICK_ALL), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        instance = null
    }

    private fun fireAllTaps(coords: FloatArray) {
        // coords = [x0, y0, x1, y1, x2, y2, ...]
        val count = coords.size / 2
        if (count == 0) {
            sendStatus(0, "No saved positions")
            return
        }
        for (i in 0 until count) {
            val x = coords[i * 2]
            val y = coords[i * 2 + 1]
            handler.postDelayed({
                performTap(x, y)
                if (i == count - 1) {
                    sendStatus(count, "Clicked $count checkboxes!")
                }
            }, (i * 350).toLong())
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun sendStatus(count: Int, message: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra("count", count)
            putExtra("message", message)
            setPackage(packageName)
        })
    }
}
