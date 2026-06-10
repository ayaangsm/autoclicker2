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
    }

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CLICK_ALL) clickAllCheckboxes()
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

    private fun clickAllCheckboxes() {
        val root = rootInActiveWindow ?: run {
            sendStatus(0, "No active window")
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val allNodes = mutableListOf<NodeInfo>()
        collectAllNodes(root, allNodes)
        root.recycle()

        val tapTargets = mutableListOf<Pair<Float, Float>>()

        for (info in allNodes) {
            val score = scoreAsCheckbox(info, screenWidth, screenHeight)
            if (score >= 3) {
                val cx = info.bounds.centerX().toFloat()
                val cy = info.bounds.centerY().toFloat()
                val dup = tapTargets.any { (px, py) ->
                    kotlin.math.abs(px - cx) < 40 && kotlin.math.abs(py - cy) < 40
                }
                if (!dup) tapTargets.add(Pair(cx, cy))
            }
        }

        if (tapTargets.isEmpty()) {
            sendStatus(0, "No checkboxes found")
            return
        }

        val sorted = tapTargets.sortedBy { it.second }

        sorted.forEachIndexed { index, (x, y) ->
            handler.postDelayed({
                performTap(x, y)
                if (index == sorted.size - 1) {
                    sendStatus(sorted.size, "Clicked ${sorted.size} checkboxes!")
                }
            }, (index * 400).toLong())
        }
    }

    private fun scoreAsCheckbox(info: NodeInfo, screenW: Int, screenH: Int): Int {
        var score = 0
        val b = info.bounds
        val w = b.width()
        val h = b.height()

        if (w <= 0 || h <= 0) return 0
        if (!info.isVisible) return 0

        val cls = info.className
        if (cls.contains("CheckBox", true) || cls.contains("CompoundButton", true)) score += 5
        if (info.isCheckable) score += 4

        val aspectRatio = w.toFloat() / h.toFloat()
        if (aspectRatio in 0.7f..1.4f) score += 2
        if (w in 40..130 && h in 40..130) score += 2
        if (b.left > screenW * 0.65f) score += 3
        if (h < screenH * 0.15f) score += 1
        if (info.isClickable) score += 1
        if (cls.contains("ImageView", true) && aspectRatio in 0.7f..1.4f
            && b.left > screenW * 0.65f) score += 2

        return score
    }

    data class NodeInfo(
        val className: String,
        val bounds: Rect,
        val isCheckable: Boolean,
        val isClickable: Boolean,
        val isVisible: Boolean,
        val isChecked: Boolean
    )

    private fun collectAllNodes(node: AccessibilityNodeInfo, result: MutableList<NodeInfo>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        result.add(NodeInfo(
            className = node.className?.toString() ?: "",
            bounds = Rect(bounds),
            isCheckable = node.isCheckable,
            isClickable = node.isClickable,
            isVisible = node.isVisibleToUser,
            isChecked = node.isChecked
        ))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, result)
            child.recycle()
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
