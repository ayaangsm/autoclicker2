package com.checkboxclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CheckboxAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "CheckboxClicker"
        const val ACTION_CLICK_ALL = "com.checkboxclicker.CLICK_ALL"
        const val ACTION_STATUS = "com.checkboxclicker.STATUS"
        var instance: CheckboxAccessibilityService? = null
        var lastClickCount = 0
    }

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CLICK_ALL) {
                clickAllCheckboxes()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter(ACTION_CLICK_ALL)
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        instance = null
    }

    fun clickAllCheckboxes() {
        val root = rootInActiveWindow ?: run {
            sendStatus(0, "No active window found")
            return
        }

        val targets = mutableListOf<AccessibilityNodeInfo>()
        collectTargets(root, targets)

        Log.d(TAG, "Found ${targets.size} targets")
        lastClickCount = targets.size

        if (targets.isEmpty()) {
            sendStatus(0, "No checkboxes found on screen")
            root.recycle()
            return
        }

        // Click each with a small delay between
        targets.forEachIndexed { index, node ->
            handler.postDelayed({
                tryClickNode(node)
                node.recycle()
                if (index == targets.size - 1) {
                    sendStatus(targets.size, "Done! Clicked ${targets.size} item(s)")
                }
            }, (index * 150).toLong())
        }

        root.recycle()
    }

    private fun collectTargets(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val w = bounds.width()
    val h = bounds.height()

    // Match small square-ish elements (checkboxes are ~60x60px)
    val isSquarish = w in 30..150 && h in 30..150 && kotlin.math.abs(w - h) < 60

    // Must be visible and either clickable or checkable
    val isTarget = (node.isClickable || node.isCheckable || node.isEnabled)
            && isSquarish
            && !bounds.isEmpty
            && node.isVisibleToUser

    if (isTarget) {
        result.add(AccessibilityNodeInfo.obtain(node))
    }

    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        collectTargets(child, result)
        child.recycle()
    }
}

    private fun tryClickNode(node: AccessibilityNodeInfo) {
        // Try accessibility click first
        if (node.isClickable) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) return
        }

        // Try clicking parent
        val parent = node.parent
        if (parent != null) {
            if (parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent.recycle()
            return
        }

        // Fallback: gesture tap at center of node
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            performTap(cx, cy)
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun sendStatus(count: Int, message: String) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra("count", count)
            putExtra("message", message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
