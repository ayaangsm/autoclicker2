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
    private var crosshairView: View? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var crosshairX = 400f
    private var crosshairY = 400f
    private var deleteIndex = -1
    private var dotsView: DotsView? = null
    private var dotsVisible = true

    private lateinit var btnStart: TextView
    private lateinit var btnAdd: TextView
    private lateinit var btnConfirm: TextView
    private lateinit var btnDelete: TextView
    private lateinit var btnDelPrev: TextView
    private lateinit var btnDelNext: TextView
    private lateinit var btnDelConfirm: TextView
    private lateinit var btnShow: TextView
    private lateinit var btnHide: TextView
    private lateinit var tvStatus: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val count = intent.getIntExtra("count", 0)
            setStatus("✓ Tapped $count!", "#00ff88")
            handler.postDelayed({ setStatus("${points.size} pts saved", "#8aabb0") }, 2500)
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
        registerReceiver(
            statusReceiver,
            IntentFilter(CheckboxAccessibilityService.ACTION_STATUS),
            Context.RECEIVER_NOT_EXPORTED
        )
        setStatus("${points.size} pts saved", "#8aabb0")
    }

    private fun createPanel() {
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F20a0c0f"))
            setPadding(6, 6, 6, 6)
        }

        btnStart      = makeBtn("▶ START",   "#00e5ff", "#000000" 14f)
        btnAdd        = makeBtn("＋ ADD",     "#00ff88", "#000000")
        btnConfirm    = makeBtn("✓ CONFIRM", "#ffaa00", "#000000")
        btnDelete     = makeBtn("✕ DELETE",  "#ff3d71", "#ffffff")
        btnDelPrev    = makeBtn("◀",         "#884444", "#ffffff")
        btnDelNext    = makeBtn("▶",         "#884444", "#ffffff")
        btnDelConfirm = makeBtn("🗑 DEL",    "#ff0000", "#ffffff")
        btnShow       = makeBtn("● SHOW",    "#444444", "#ffffff")
        btnHide       = makeBtn("○ HIDE",    "#222222", "#aaaaaa")

        tvStatus = TextView(this).apply {
            text = "..."
            textSize = 15f
            setTextColor(Color.parseColor("#8aabb0"))
            gravity = Gravity.CENTER
            setPadding(0, 3, 0, 0)
        }

        panelView!!.apply {
            addView(btnStart)
            addView(gap())
            addView(btnAdd)
            addView(btnConfirm)
            addView(gap())
            addView(btnDelete)
            // PREV and NEXT side by side
            val delRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            delRow.addView(btnDelPrev)
            delRow.addView(btnDelNext)
            addView(delRow)
            addView(btnDelConfirm)
            addView(gap())
            addView(btnShow)
            addView(btnHide)
            addView(tvStatus)
        }

        btnConfirm.visibility    = View.GONE
        btnDelPrev.visibility    = View.GONE
        btnDelNext.visibility    = View.GONE
        btnDelConfirm.visibility = View.GONE

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 4
            y = 0
        }

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

        btnStart.setOnClickListener      { onStartClick() }
        btnAdd.setOnClickListener        { onAddClick() }
        btnConfirm.setOnClickListener    { onConfirmClick() }
        btnDelete.setOnClickListener     { onDeleteClick() }
        btnDelPrev.setOnClickListener    { onDelPrev() }
        btnDelNext.setOnClickListener    { onDelNext() }
        btnDelConfirm.setOnClickListener { onDelConfirm() }
        btnShow.setOnClickListener       { onShowClick() }
        btnHide.setOnClickListener       { onHideClick() }

        wm.addView(panelView, panelParams)
    }

    private fun onStartClick() {
        cancelMode()
        if (points.isEmpty()) { setStatus("No points saved!", "#ff9900"); return }
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
        btnAdd.text = "✕ CANCEL"
        btnAdd.setBackgroundColor(Color.parseColor("#888888"))
        btnConfirm.visibility = View.VISIBLE
        setStatus("Drag ✛ to checkbox → CONFIRM", "#ffaa00")
        showCrosshair()
    }

    private fun onConfirmClick() {
        points.add(Pair(crosshairX, crosshairY))
        savePoints()
        refreshDots()
        setStatus("Saved #${points.size} — drag to next or CANCEL", "#00ff88")
    }

    private fun onDeleteClick() {
        if (mode == Mode.DELETE) { cancelMode(); return }
        if (points.isEmpty()) { setStatus("No points to delete!", "#ff9900"); return }
        cancelMode()
        mode = Mode.DELETE
        deleteIndex = 0
        btnDelete.text = "✕ CANCEL"
        btnDelete.setBackgroundColor(Color.parseColor("#888888"))
        btnDelPrev.visibility    = View.VISIBLE
        btnDelNext.visibility    = View.VISIBLE
        btnDelConfirm.visibility = View.VISIBLE
        dotsVisible = true
        refreshDots()
        highlightDelete()
    }

    private fun onDelPrev() {
        if (points.isEmpty()) return
        deleteIndex = (deleteIndex - 1 + points.size) % points.size
        highlightDelete()
    }

    private fun onDelNext() {
        if (points.isEmpty()) return
        deleteIndex = (deleteIndex + 1) % points.size
        highlightDelete()
    }

    private fun onDelConfirm() {
        if (deleteIndex < 0 || deleteIndex >= points.size) return
        points.removeAt(deleteIndex)
        savePoints()
        if (points.isEmpty()) { cancelMode(); setStatus("All cleared", "#ff3d71"); return }
        deleteIndex = deleteIndex.coerceAtMost(points.size - 1)
        refreshDots()
        highlightDelete()
    }

    private fun highlightDelete() {
        dotsView?.setHighlight(deleteIndex)
        setStatus("#${deleteIndex+1} of ${points.size} — DEL or ◀▶", "#ff3d71")
    }

    private fun onShowClick() {
        dotsVisible = true
        dotsView?.setHighlight(-1)
        refreshDots()
        btnShow.setBackgroundColor(Color.parseColor("#00e5ff"))
        btnHide.setBackgroundColor(Color.parseColor("#222222"))
        setStatus("Showing ${points.size} dots", "#8aabb0")
    }

    private fun onHideClick() {
        dotsVisible = false
        refreshDots()
        btnShow.setBackgroundColor(Color.parseColor("#444444"))
        btnHide.setBackgroundColor(Color.parseColor("#00e5ff"))
        setStatus("Hidden — ${points.size} active", "#8aabb0")
    }

    private fun cancelMode() {
        mode = Mode.NONE
        deleteIndex = -1
        hideCrosshair()
        btnAdd.text = "＋ ADD"
        btnAdd.setBackgroundColor(Color.parseColor("#00ff88"))
        btnConfirm.visibility    = View.GONE
        btnDelete.text = "✕ DELETE"
        btnDelete.setBackgroundColor(Color.parseColor("#ff3d71"))
        btnDelPrev.visibility    = View.GONE
        btnDelNext.visibility    = View.GONE
        btnDelConfirm.visibility = View.GONE
        dotsView?.setHighlight(-1)
        refreshDots()
        setStatus("${points.size} pts saved", "#8aabb0")
    }

    private fun showCrosshair() {
        hideCrosshair()
        val size = 120
        val ch = object : View(this) {
            private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#ff3d71"); strokeWidth = 4f; style = Paint.Style.STROKE }
            private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#44ff3d71"); style = Paint.Style.FILL }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                canvas.drawCircle(cx, cy, 38f, fp)
                canvas.drawCircle(cx, cy, 38f, lp)
                canvas.drawLine(cx - 52f, cy, cx + 52f, cy, lp)
                canvas.drawLine(cx, cy - 52f, cx, cy + 52f, lp)
            }
        }

        crosshairParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 300; y = 400
        }
        crosshairX = 300f + size / 2f
        crosshairY = 400f + size / 2f

        var itx = 0f; var ity = 0f; var iox = 0; var ioy = 0
        ch.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    itx = e.rawX; ity = e.rawY
                    iox = crosshairParams!!.x; ioy = crosshairParams!!.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    crosshairParams!!.x = iox + (e.rawX - itx).toInt()
                    crosshairParams!!.y = ioy + (e.rawY - ity).toInt()
                    crosshairX = crosshairParams!!.x + size / 2f
                    crosshairY = crosshairParams!!.y + size / 2f
                    wm.updateViewLayout(ch, crosshairParams); true
                }
                else -> false
            }
        }

        wm.addView(ch, crosshairParams)
        crosshairView = ch
    }

    private fun hideCrosshair() {
        crosshairView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        crosshairView = null
    }

    inner class DotsView(context: Context) : View(context) {
        private var highlight = -1
        fun setHighlight(i: Int) { highlight = i; postInvalidate() }

        private val fill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC00e5ff"); style = Paint.Style.FILL }
        private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCff3d71"); style = Paint.Style.FILL }
        private val ring   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val num    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        override fun onDraw(canvas: Canvas) {
            if (!dotsVisible) return
            points.forEachIndexed { i, (x, y) ->
                canvas.drawCircle(x, y, 26f, if (i == highlight) hlFill else fill)
                canvas.drawCircle(x, y, 26f, ring)
                canvas.drawText("${i+1}", x, y + 9f, num)
            }
        }
    }

    private fun createDotsView() {
        dotsView = DotsView(this)
        wm.addView(dotsView, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT))
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
        text = label
        textSize = 10f
        setTextColor(Color.parseColor(fg))
        setBackgroundColor(Color.parseColor(bg))
        setPadding(8, 6, 8, 6)
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 3) }
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 5) }

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
