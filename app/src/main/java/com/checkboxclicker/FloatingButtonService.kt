package com.checkboxclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: TextView
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra("message") ?: ""
            val count = intent.getIntExtra("count", 0)
            // Flash button green briefly to confirm
            floatingView.text = "✓ $count"
            floatingView.setBackgroundColor(Color.parseColor("#00cc55"))
            floatingView.postDelayed({
                floatingView.text = "CLICK"
                floatingView.setBackgroundColor(Color.parseColor("#00e5ff"))
            }, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingButton()
        registerReceiver(
            statusReceiver,
            IntentFilter(CheckboxAccessibilityService.ACTION_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun createFloatingButton() {
        floatingView = TextView(this).apply {
            text = "CLICK"
            textSize = 13f
            setTextColor(Color.parseColor("#0a0c0f"))
            setBackgroundColor(Color.parseColor("#00e5ff"))
            setPadding(28, 18, 28, 18)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        floatingView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // It's a tap — trigger click all
                        floatingView.text = "..."
                        floatingView.setBackgroundColor(Color.parseColor("#ff9900"))
                        sendBroadcast(Intent(CheckboxAccessibilityService.ACTION_CLICK_ALL).apply {
                            setPackage(packageName)
                        })
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun buildNotification(): Notification {
        val channelId = "checkbox_clicker"
        val channel = NotificationChannel(channelId, "Checkbox Clicker", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Checkbox Clicker Active")
            .setContentText("Floating button is visible. Tap it to click checkboxes.")
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            try { windowManager.removeView(floatingView) } catch (e: Exception) {}
        }
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
