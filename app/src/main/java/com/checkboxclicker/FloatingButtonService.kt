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
            handler.postDelayed({ setStatus("Ready — ${points.size} saved", "#8aabb0") }, 2500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("clicker_prefs", Context.MODE_PRIVATE)
        loadPoints()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        createPanel()
        createDotsView()
        registerReceiver(statusReceiver,
            IntentFilter(CheckboxAccessibilityService.ACTION_STATUS),
            Context.RECEIVER_NOT_EXPORTED)
        setStatus("Ready — ${points.size} saved", "#8aabb0")
    }

    private fun createPanel() {
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0a0c0f"))
            setPadding(10, 10, 10, 10)
        }

        btnStart  = makeBtn("▶ START",   "#00e5ff", "#000000")
        btnAdd    = makeBtn("＋ ADD",     "#00ff88", "#000000")
        btnDelete = makeBtn("✕ DELETE",  "#ff3d71", "#ffffff")
        btnShow   = makeBtn("● SHOW",    "#555555", "#ffffff")
        btnHide   = makeBtn("○ HIDE",    "#333333", "#aaaaaa")
        tvStatus  = TextView(this).apply {
            text = "..."
            textSize = 9f
            setTextColor(Color.parseColor("#8aabb0"))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 2)
        }

        panelView!!.apply {
            addView(btnStart)
            addView(space())
            addView(btnAdd)
            addView(btnDelete)
            addView(space())
            addView(btnShow)
            addView(btnHide)
            addView(tvStatus)
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 350 }

        var ix = 0; var iy = 0; var itx = 0f; var ity = 0f
        panelView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = panelParams!!.x; iy = panelParams!!.y
                    itx = e.rawX; ity = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams!!.x = ix - (e.rawX - itx).toInt()
                    panelParams!!.y = iy + (e.rawY - ity).toInt()
                    wm.updateViewLayout(panelView, panelParams)
                    true
                }
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
        if (points.isEmpty()) { setStatus("No saved points!", "#ff9900"); return }
        cancelMode()
        val coords = FloatArray(points.size * 2)
        points.forEachIndexed { i, (x, y) -> coords[i*2] = x; coords[i*2+1] = y }
        setStatus("Clicking ${points.size}...", "#ff9900")
        sendBroadcast(Intent(CheckboxAccessibilityService.ACTION_CLICK_ALL).apply {
            putExtra(CheckboxAccessibilityService.EXTRA_COORDS, coords)
            setPackage(packageName)
        })
    }

    private fun onAddClick() {
        if (mode == Mode.ADD) { cancelMode(); return }
        cancelMode()
        mode = Mode.ADD
        btnAdd.setBackgroundColor(Color.parseColor("#ffaa00"))
        btnAdd.text = "✓ DONE"
        setStatus("TAP once per checkbox", "#ffaa00")
        installTouchOverlay { x, y ->
            points.add(Pair(x, y))
            savePoints()
            refreshDots()
            setStatus("Added #${points.size} — tap more or DONE", "#00ff88")
        }
    }

    private fun onDeleteClick() {
        if (mode == Mode.DELETE) { cancelMode(); return }
        cancelMode()
        mode = Mode.DELETE
        btnDelete.setBackgroundColor(Color.parseColor("#ff0000"))
        btnDelete.text = "✓ DONE"
        dotsVisible = true
        refreshDots()
        setStatus("TAP a dot to delete it", "#ff3d71")
        installTouchOverlay { x, y ->
            val nearest = points.minByOrNull { (px, py) ->
                Math.sqrt(((px-x)*(px-x) + (py-y)*(py-y)).toDouble())
            }
            if (nearest != null) {
                val dist = Math.sqrt(((nearest.first-x)*(nearest.first-x) +
                        (nearest.second-y)*(nearest.second-y)).toDouble())
                if (dist < 80) {
                    points.remove(nearest)
                    savePoints()
                    refreshDots()
                    setStatus("Deleted. ${points.size} remaining", "#ff3d71")
                } else {
                    setStatus("Tap closer to a dot", "#ff9900")
                }
            }
        }
    }

    private fun onShowClick() {
        dotsVisible = true
        refreshDots()
        btnShow.setBackgroundColor(Color.parseColor("#00e5ff"))
        btnHide.setBackgroundColor(Color.parseColor("#333333"))
        setStatus("Showing ${points.size} dots", "#8aabb0")
    }

    private fun onHideClick() {
        dotsVisible = false
        refreshDots()
        btnShow.setBackgroundColor(Color.parseColor("#555555"))
        btnHide.setBackgroundColor(Color.parseColor("#00e5ff"))
        setStatus("Dots hidden — ${points.size} saved", "#8aabb0")
    }

    private fun cancelMode() {
        mode = Mode.NONE
        btnAdd.text = "＋ ADD"
        btnAdd.setBackgroundColor(Color.parseColor("#00ff88"))
        btnDelete.text = "✕ DELETE"
        btnDelete.setBackgroundColor(Color.parseColor("#ff3d71"))
        removeTouchOverlay()
        setStatus("Ready — ${points.size} saved", "#8aabb0")
    }

    private fun installTouchOverlay(onTap: (Float, Float) -> Unit) {
        removeTouchOverlay()
        val v = object : View(this) {}
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) onTap(e.rawX, e.rawY)
            true
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, p)
        touchOverlay = v
    }

    private fun removeTouchOverlay() {
        touchOverlay?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        touchOverlay = null
    }

    inner class DotsView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC00e5ff"); style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 26f
            textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        override fun onDraw(canvas: Canvas) {
            if (!dotsVisible) return
            points.forEachIndexed { i, (x, y) ->
                canvas.drawCircle(x, y, 32f, fill)
                canvas.drawCircle(x, y, 32f, ring)
                canvas.drawText("${i+1}", x, y + 10f, txt)
            }
        }
    }

    private fun createDotsView() {
        val v = DotsView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        wm.addView(v, p)
        dotsView = v
    }

    private fun refreshDots() { dotsView?.invalidate() }

    private fun savePoints() {
        prefs.edit().putString("points",
            points.joinToString("|") { "${it.first},${it.second}" }).apply()
    }

    private fun loadPoints() {
        points.clear()
        (prefs.getString("points", "") ?: "").split("|").forEach { part ->
            val xy = part.split(",")
            if (xy.size == 2) try {
                points.add(Pair(xy[0].toFloat(), xy[1].toFloat()))
            } catch (e: Exception) {}
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

    private fun space() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 8) }

    private fun buildNotification(): Notification {
        val id = "chk"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, "Checkbox Clicker",
                NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, id)
            .setContentTitle("Checkbox Clicker")
            .setContentText("Panel active")
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelMode()
        panelView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        dotsView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
