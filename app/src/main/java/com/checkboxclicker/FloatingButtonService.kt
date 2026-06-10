package com.checkboxclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: LinearLayout
    private lateinit var btnClick: TextView
    private lateinit var btnAdd: TextView
    private lateinit var btnClear: TextView
    private lateinit var tvCount: TextView

    // Overlay that captures taps in ADD mode
    private var addOverlay: View? = null

    private var isAddMode = false
    private val savedPoints = mutableListOf<Pair<Float, Float>>()
    private lateinit var prefs: SharedPreferences

    // Dots overlay to show saved positions
    private var dotsOverlay: DotsOverlayView? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("message") ?: return
            val count = intent.getIntExtra("count", 0)
            flashButton(btnClick, "✓ $count", "#00cc55")
            handler.postDelayed({ resetClickButton() }, 2000)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("clicker_prefs", Context.MODE_PRIVATE)
        loadSavedPoints()
        startForeground(1, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createPanel()
        registerReceiver(
            statusReceiver,
            IntentFilter(CheckboxAccessibilityService.ACTION_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun createPanel() {
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0a0c0f"))
            setPadding(8, 8, 8, 8)
        }

        btnClick = makeButton("CLICK", "#00e5ff", "#0a0c0f")
        btnAdd = makeButton("+ ADD", "#00ff88", "#0a0c0f")
        btnClear = makeButton("CLR", "#ff3d71", "#ffffff")
        tvCount = TextView(this).apply {
            text = "${savedPoints.size} pts"
            textSize = 9f
            setTextColor(Color.parseColor("#8aabb0"))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }

        panelView.addView(btnClick)
        panelView.addView(btnAdd)
        panelView.addView(btnClear)
        panelView.addView(tvCount)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 300
        }

        // Make panel draggable
        var ix = 0; var iy = 0; var itx = 0f; var ity = 0f; var dragging = false
        panelView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    itx = event.rawX; ity = event.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - itx) > 5 ||
                        kotlin.math.abs(event.rawY - ity) > 5) dragging = true
                    params.x = ix - (event.rawX - itx).toInt()
                    params.y = iy + (event.rawY - ity).toInt()
                    windowManager.updateViewLayout(panelView, params)
                    true
                }
                else -> false
            }
        }

        btnClick.setOnClickListener {
            if (savedPoints.isEmpty()) {
                flashButton(btnClick, "No pts!", "#ff9900")
                handler.postDelayed({ resetClickButton() }, 1500)
                return@setOnClickListener
            }
            // Build coords array and fire
            val coords = FloatArray(savedPoints.size * 2)
            savedPoints.forEachIndexed { i, (x, y) ->
                coords[i * 2] = x
                coords[i * 2 + 1] = y
            }
            flashButton(btnClick, "...", "#ff9900")
            sendBroadcast(Intent(CheckboxAccessibilityService.ACTION_CLICK_ALL).apply {
                putExtra(CheckboxAccessibilityService.EXTRA_COORDS, coords)
                setPackage(packageName)
            })
        }

        btnAdd.setOnClickListener {
            if (!isAddMode) startAddMode() else stopAddMode()
        }

        btnClear.setOnClickListener {
            savedPoints.clear()
            savePoints()
            updateCount()
            removeDotsOverlay()
            flashButton(btnClear, "✓", "#00ff88")
            handler.postDelayed({ btnClear.text = "CLR" }, 1000)
        }

        windowManager.addView(panelView, params)
        updateDotsOverlay()
    }

    private fun startAddMode() {
        isAddMode = true
        btnAdd.text = "DONE"
        btnAdd.setBackgroundColor(Color.parseColor("#ff9900"))

        // Full screen transparent overlay to catch taps
        val overlay = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // Draw semi-transparent hint
                val paint = Paint().apply {
                    color = Color.parseColor("#330000ff")
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX
                val y = event.rawY
                savedPoints.add(Pair(x, y))
                savePoints()
                updateCount()
                updateDotsOverlay()
                true
            } else false
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlay, overlayParams)
        addOverlay = overlay
    }

    private fun stopAddMode() {
        isAddMode = false
        btnAdd.text = "+ ADD"
        btnAdd.setBackgroundColor(Color.parseColor("#00ff88"))
        addOverlay?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        addOverlay = null
    }

    // Dots overlay to show saved tap positions
    inner class DotsOverlayView(context: Context) : View(context) {
        private val dotPaint = Paint().apply {
            color = Color.parseColor("#CC00e5ff")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val ringPaint = Paint().apply {
            color = Color.parseColor("#00e5ff")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            savedPoints.forEachIndexed { index, (x, y) ->
                canvas.drawCircle(x, y, 22f, dotPaint)
                canvas.drawCircle(x, y, 22f, ringPaint)
                canvas.drawText("${index + 1}", x, y + 9f, textPaint)
            }
        }
    }

    private fun updateDotsOverlay() {
        removeDotsOverlay()
        if (savedPoints.isEmpty()) return

        val dots = DotsOverlayView(this)
        val dotsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(dots, dotsParams)
        dotsOverlay = dots
    }

    private fun removeDotsOverlay() {
        dotsOverlay?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        dotsOverlay = null
    }

    private fun makeButton(text: String, bgColor: String, textColor: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor(textColor))
            setBackgroundColor(Color.parseColor(bgColor))
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
            layoutParams = lp
        }
    }

    private fun flashButton(btn: TextView, msg: String, color: String) {
        btn.text = msg
        btn.setBackgroundColor(Color.parseColor(color))
    }

    private fun resetClickButton() {
        btnClick.text = "CLICK"
        btnClick.setBackgroundColor(Color.parseColor("#00e5ff"))
    }

    private fun updateCount() {
        tvCount.text = "${savedPoints.size} pts"
    }

    private fun savePoints() {
        val str = savedPoints.joinToString("|") { "${it.first},${it.second}" }
        prefs.edit().putString("points", str).apply()
    }

    private fun loadSavedPoints() {
        savedPoints.clear()
        val str = prefs.getString("points", "") ?: return
        if (str.isEmpty()) return
        str.split("|").forEach { part ->
            val xy = part.split(",")
            if (xy.size == 2) {
                try {
                    savedPoints.add(Pair(xy[0].toFloat(), xy[1].toFloat()))
                } catch (e: Exception) {}
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "checkbox_clicker"
        val channel = NotificationChannel(channelId, "Checkbox Clicker",
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Checkbox Clicker Active")
            .setContentText("Tap + ADD to mark checkboxes, CLICK to tap them all")
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAddMode()
        removeDotsOverlay()
        try { windowManager.removeView(panelView) } catch (e: Exception) {}
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
