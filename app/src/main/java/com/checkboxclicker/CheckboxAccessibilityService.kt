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
        const val ACTION_STATUS    = "com.checkboxclicker.STATUS"
        const val EXTRA_COORDS     = "coords"
        var instance: CheckboxAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CLICK_ALL) return
            val coords = intent.getFloatArrayExtra(EXTRA_COORDS) ?: return
            val count = coords.size / 2
            if (count == 0) return
            for (i in 0 until count) {
                val x = coords[i * 2]
                val y = coords[i * 2 + 1]
                handler.postDelayed({ tap(x, y) }, i * 100L)
            }
            handler.postDelayed({
                sendBroadcast(Intent(ACTION_STATUS).apply {
                    putExtra("count", count)
                    setPackage(packageName)
                })
            }, count * 100L)
        }
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(receiver, IntentFilter(ACTION_CLICK_ALL),
            Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        instance = null
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
