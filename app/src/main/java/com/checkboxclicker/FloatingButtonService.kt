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
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class FloatingButtonService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val points = mutableListOf<Pair<Float, Float>>()

    private var mode = Mode.NONE
    enum class Mode { NONE, ADD, DELETE }

    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var touchOverlay: View? = null
    private var dotsView: DotsView? = null
    private var dotsVisible = true

    private lateinit var btnStart: TextView
    private lateinit var btnAdd: TextView
    private lateinit var btnDelete: TextView
    private lateinit var btnShow: TextView
    private lateinit var btnHide: TextView
    private lateinit var tvStatus: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val count = intent.getIntExtra("count", 0)
            setStatus("✓ Clicked $count!", "#00ff88")
            handler.postDelayed({ setStatus("Ready — ${points.size} pts", "#8aabb0") }, 2500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("clicker_prefs", Context.MODE_PRIVATE)
        loadPoints()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        createDotsView()
        createPanel()
        registerReceiver(statusReceiver,
            IntentFilter(CheckboxAccessibilityService.ACTION_STATUS),
            Context.RECEIVER_NOT_EXPORTED)
        setStatus("Ready — ${points.size} pts", "#8aabb0")
    }

    private fun createPanel() {
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F00a0c0f"))
            setPadding(10, 10, 10, 10)
        }

        btnStart  = makeBtn("▶ START",  "#00e5ff", "#000000")
        btnAdd    = makeBtn("＋ ADD",    "#00ff88", "#000000")
        btnDelete = makeBtn("✕ DELETE", "#ff3d71", "#ffffff")
        btnShow   = makeBtn("● SHOW",   "#444444", "#ffffff")
        btnHide   = makeBtn("○ HIDE",   "#222222", "#aaaaaa")
        tvStatus  = TextView(this).apply {
            text = "..."
            textSize = 9f
            setTextColor(Color.parseColor("#8aabb0"))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }

        panelView!!.apply {
            addView(btnStart);  addView(gap())
            addView(btnAdd);    addView(btnDelete); addView(gap())
            addView(btnShow);   addView(btnHide)
            addView(tvStatus)
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 4; y = 380 }

        var ix = 0; var iy = 0; var itx = 0f; var ity = 0f; var dragging = false
        panelView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = panelParams!!.x; iy = panelParams!!.y
                    itx = e.rawX; ity = e.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - itx).toInt()
                    val dy = (e.rawY - ity).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragging = true
                    panelParams!!.x = ix - dx
                    panelParams!!.y = iy + dy
                    wm.updateViewLayout(panelView, panelParams)
                    true
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }

        btnStart.setOnClickListener  { onStartClick() }
        btnAdd.setOnClickListener    { onAddClick() }
        btnDelete.setOnClickListener { onDeleteClick() }
        btnShow.setOnClickListener   { onShowClick() }
        btnHide.setOnClickListener   { onHideClick() }

        wm.addView(panelView, panelParams)
    }

    private fun onStartClick() {
        if (points.isEmpty()) { setStatus("Add points first!", "#ff9900"); return }
        cancelMode()
        val coords = FloatArray(points.size * 2)
        points.forEachIndexed { i, (x, y) -> coords[i*2] = x; coords[i*2+1] = y }
        setStatus("Firing ${points.size} taps...", "#ff9900")
        sendBroadcast(Intent(CheckboxAccessibilityService.ACTION_CLICK_ALL).apply {
            putExtra(CheckboxAccessibilityService.EXTRA_COORDS, coords)
            setPackage(packageName)
        })
    }

    private fun onAddClick() {
        if (mode == Mode.ADD) { cancelMode(); return }
        cancelMode()
        mode = Mode.ADD
        btnAdd.text = "✓ DONE"
        btnAdd.setBackgroundColor(Color.parseColor("#ffaa00"))
        setStatus("Tap each checkbox once, then DONE", "#ffaa00")
        installOverlay { x, y ->
            points.add(Pair(x, y))
            savePoints(); refreshDots()
            setStatus("Added #${points.size} — tap DONE when finished", "#00ff88")
        }
    }

    private fun onDeleteClick() {
        if (mode == Mode.DELETE) { cancelMode(); return }
        cancelMode()
        mode = Mode.DELETE
        btnDelete.text = "✓ DONE"
        btnDelete.setBackgroundColor(Color.parseColor("#cc0000"))
        dotsVisible = true; refreshDots()
        setStatus("Tap a numbered dot to delete it", "#ff3d71")
        installOverlay { x, y ->
            val nearest = points.minByOrNull { (px, py) ->
                Math.sqrt(((px-x)*(px-x)+(py-y)*(py-y)).toDouble()) }
            if (nearest != null) {
                val dist = Math.sqrt(((nearest.first-x)*(nearest.first-x)+
                        (nearest.second-y)*(nearest.second-y)).toDouble())
                if (dist < 80) {
                    points.remove(nearest); savePoints(); refreshDots()
                    setStatus("Deleted. ${points.size} remaining", "#ff3d71")
                } else setStatus("Tap closer to a dot", "#ff9900")
            }
        }
    }

    private fun onShowClick() {
        dotsVisible = true; refreshDots()
        btnShow.setBackgroundColor(Color.parseColor("#00e5ff"))
        btnHide.setBackgroundColor(Color.parseColor("#222222"))
        setStatus("Showing ${points.size} dots", "#8aabb0")
    }

    private fun onHideClick() {
        dotsVisible = false; refreshDots()
        btnShow.setBackgroundColor(Color.parseColor("#444444"))
        btnHide.setBackgroundColor(Color.parseColor("#00e5ff"))
        setStatus("Dots hidden — still active", "#8aabb0")
    }

    private fun cancelMode() {
        mode = Mode.NONE
        removeTouchOverlay()
        btnAdd.text = "＋ ADD"; btnAdd.setBackgroundColor(Color.parseColor("#00ff88"))
        btnDelete.text = "✕ DELETE"; btnDelete.setBackgroundColor(Color.parseColor("#ff3d71"))
        setStatus("Ready — ${points.size} pts", "#8aabb0")
    }

    // Overlay that passes taps through to panel buttons
    private fun installOverlay(onTap: (Float, Float) -> Unit) {
        removeTouchOverlay()
        val overlay = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.action != MotionEvent.ACTION_DOWN) return true
                // If tap hits the panel — don't consume, let panel handle it
                if (isTapOnPanel(e.rawX, e.rawY)) return false
                onTap(e.rawX, e.rawY)
                return true
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        wm.addView(overlay, p)
        touchOverlay = overlay
    }

    private fun isTapOnPanel(rawX: Float, rawY: Float): Boolean {
        val v = panelView ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val rect = Rect(loc[0] - 20, loc[1] - 20,
            loc[0] + v.width + 20, loc[1] + v.height + 20)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun removeTouchOverlay() {
        touchOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        touchOverlay = null
    }

    inner class DotsView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC00e5ff"); style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 28f
            textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        override fun onDraw(canvas: Canvas) {
            if (!dotsVisible) return
            points.forEachIndexed { i, (x, y) ->
                canvas.drawCircle(x, y, 30f, fill)
                canvas.drawCircle(x, y, 30f, ring)
                canvas.drawText("${i+1}", x, y + 10f, num)
            }
        }
    }

    private fun createDotsView() {
        dotsView = DotsView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        wm.addView(dotsView, p)
    }

    private fun refreshDots() { dotsView?.postInvalidate() }

    private fun savePoints() {
        prefs.edit().putString("points",
            points.joinToString("|") { "${it.first},${it.second}" }).apply()
    }

    private fun loadPoints() {
        points.clear()
        val s = prefs.getString("points", "") ?: return
        if (s.isBlank()) return
        s.split("|").forEach { part ->
            val xy = part.split(",")
            if (xy.size == 2) try {
                points.add(Pair(xy[0].toFloat(), xy[1].toFloat()))
            } catch (_: Exception) {}
        }
    }

    private fun setStatus(msg: String, color: String) {
        tvStatus.text = msg
        tvStatus.setTextColor(Color.parseColor(color))
    }

    private fun makeBtn(label: String, bg: String, fg: String) = TextView(this).apply {
        text = label; textSize = 11f
        setTextColor(Color.parseColor(fg))
        setBackgroundColor(Color.parseColor(bg))
        setPadding(18, 14, 18, 14); gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 4) }
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 8) }

    private fun buildNotification(): Notification {
        val id = "chk"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id,
                "Checkbox Clicker", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, id)
            .setContentTitle("Checkbox Clicker Active")
            .setContentText("Tap + ADD to mark, START to click all")
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelMode()
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dotsView?.let  { try { wm.removeView(it) } catch (_: Exception) {} }
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
