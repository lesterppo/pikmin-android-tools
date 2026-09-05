package com.pikminbot.tools

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Minimal control screen for the single EngineService.
 * Toggle Pin and Jog (live-switchable within the one engine service),
 * show live state from the EngineService statics + last self-heal note.
 *
 * Also the landing target for MockLocationReceiver's autostart path
 * (foreground-service start needs an Activity context on Android 14+).
 */
class MainActivity : Activity() {

    private lateinit var pinBtn: Button
    private lateinit var jogBtn: Button
    private lateinit var status: TextView
    private lateinit var healNote: TextView
    private lateinit var coords: TextView
    private val poll = Handler(Looper.getMainLooper())

    private val pollTask = object : Runnable {
        override fun run() {
            refreshStatus()
            poll.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "PikminBot Tools"
            textSize = 22f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(title)

        pinBtn = Button(this).apply { text = "Pin: ?" }
        jogBtn = Button(this).apply { text = "Jog: ?" }
        root.addView(pinBtn)
        root.addView(jogBtn)

        status = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        root.addView(status)

        coords = TextView(this)
        root.addView(coords)

        healNote = TextView(this).apply { setTextColor(Color.rgb(150, 60, 0)) }
        root.addView(healNote)

        setContentView(root)

        pinBtn.setOnClickListener {
            if (EngineService.running && EngineService.mode == "pin") {
                EngineService.stop(this)
                Toast.makeText(this, "Engine stopped", Toast.LENGTH_SHORT).show()
            } else {
                val lat = if (EngineService.curLat != 0.0) EngineService.curLat else 22.3193
                val lon = if (EngineService.curLon != 0.0) EngineService.curLon else 114.1694
                EngineService.startPin(this, lat, lon, persistent = true)
                Toast.makeText(this, "Pin started (persistent)", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

        jogBtn.setOnClickListener {
            if (EngineService.running && EngineService.mode == "jog") {
                EngineService.stop(this)
                Toast.makeText(this, "Jog stopped", Toast.LENGTH_SHORT).show()
            } else {
                val lat = if (EngineService.curLat != 0.0) EngineService.curLat else 22.3193
                val lon = if (EngineService.curLon != 0.0) EngineService.curLon else 114.1694
                EngineService.startJog(this, lat, lon, "loop",
                    10.0, 2.0, 100.0, 0.0, 30L)
                Toast.makeText(this, "Jog started (30min loop)", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

        requestLocationPerms()
        poll.postDelayed(pollTask, 300)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Autostart path from MockLocationReceiver (Activity is the FGS-safe context). */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (!i.getBooleanExtra(EXTRA_AUTOSTART, false)) return
        val lat = readCoord(i, "lat", "latitude")
        val lon = readCoord(i, "lon", "lng", "longitude")
        if (lat.isNaN() || lon.isNaN()) return
        EngineService.startPin(this, lat, lon, persistent = false)
        Toast.makeText(this, String.format(Locale.US, "Pinning %.6f, %.6f", lat, lon), Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        pinBtn.text = "Pin: ${if (EngineService.running && EngineService.mode == "pin") "ON" else "off"}"
        jogBtn.text = "Jog: ${if (EngineService.running && EngineService.mode == "jog") "ON" else "off"}"

        status.text = buildString {
            append("Mode: ${EngineService.mode} · ticks ${EngineService.tickCount}")
            append("  ·  ${"%.0f".format(EngineService.totalMeters)}m / ${EngineService.totalSteps} steps")
            if (EngineService.lastError.isNotBlank()) append("\nEngine err: ${EngineService.lastError}")
        }

        coords.text = String.format(Locale.US,
            "Loc: %.6f, %.6f", EngineService.curLat, EngineService.curLon)

        healNote.text = if (SelfHeal.lastSecurityExceptionMs > 0L) {
            val ago = ((System.currentTimeMillis() - SelfHeal.lastSecurityExceptionMs) / 1000).coerceAtLeast(0)
            "SecurityException ${ago}s ago · self-heal attempts ${SelfHeal.attempts}/3 (last: ${SelfHeal.lastRepair.ifBlank { "none" }})"
        } else {
            "No SecurityException seen this session."
        }
    }

    private fun requestLocationPerms() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun readCoord(i: Intent?, vararg keys: String): Double {
        if (i == null) return Double.NaN
        for (k in keys) {
            if (!i.hasExtra(k)) continue
            val v: Any? = try { i.extras?.get(k) } catch (t: Throwable) { null }
            if (v is Number) return v.toDouble()
            try { return i.getDoubleExtra(k, Double.NaN) } catch (t: Throwable) {}
            try { return i.getFloatExtra(k, Float.NaN).toDouble() } catch (t: Throwable) {}
        }
        return Double.NaN
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    override fun onDestroy() {
        super.onDestroy()
        poll.removeCallbacks(pollTask)
    }
}
