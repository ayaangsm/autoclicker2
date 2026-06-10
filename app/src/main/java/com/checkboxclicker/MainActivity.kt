package com.checkboxclicker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessibility: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnToggleFloat: Button
    private var floatRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvOverlay = findViewById(R.id.tvOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnToggleFloat = findViewById(R.id.btnToggleFloat)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        btnToggleFloat.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                tvAccessibility.text = "❌ Enable Accessibility Service first!"
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                tvOverlay.text = "❌ Enable Overlay Permission first!"
                return@setOnClickListener
            }
            if (floatRunning) {
                stopService(Intent(this, FloatingButtonService::class.java))
                floatRunning = false
                btnToggleFloat.text = "▶ SHOW FLOATING BUTTON"
                btnToggleFloat.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            } else {
                startForegroundService(Intent(this, FloatingButtonService::class.java))
                floatRunning = true
                btnToggleFloat.text = "⏹ HIDE FLOATING BUTTON"
                btnToggleFloat.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (isAccessibilityEnabled()) {
            tvAccessibility.text = "✅ Accessibility Service: ENABLED"
            tvAccessibility.setTextColor(getColor(android.R.color.holo_green_dark))
            btnAccessibility.visibility = View.GONE
        } else {
            tvAccessibility.text = "❌ Accessibility: DISABLED — tap to enable"
            tvAccessibility.setTextColor(getColor(android.R.color.holo_red_dark))
            btnAccessibility.visibility = View.VISIBLE
        }

        if (Settings.canDrawOverlays(this)) {
            tvOverlay.text = "✅ Overlay Permission: GRANTED"
            tvOverlay.setTextColor(getColor(android.R.color.holo_green_dark))
            btnOverlay.visibility = View.GONE
        } else {
            tvOverlay.text = "❌ Overlay Permission: DENIED — tap to grant"
            tvOverlay.setTextColor(getColor(android.R.color.holo_red_dark))
            btnOverlay.visibility = View.VISIBLE
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${CheckboxAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(service, ignoreCase = true)) return true
        }
        return false
    }
}
