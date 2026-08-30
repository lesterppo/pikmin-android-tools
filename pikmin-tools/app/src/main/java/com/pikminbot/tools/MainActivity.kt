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
 * Minimal control screen: toggle Mock and Jog, see live state from both
 * services (polled from their static state objects), plus the last
 * SecurityException / self-heal note from SelfHeal.
 *
 * Also the landing target for MockLocationReceiver's autostart path
 * (foreground-service start needs an Activity context on Android 14+).
 */
class MainActivity : Activity() {

    private lateinit var mockBtn: Button
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

        mockBtn = Button(this).apply { text = "Mock: ?" }
        jogBtn = Button(this).apply { text = "Jog: ?" }
        root.addView(mockBtn)
        root.addView(jogBtn)

        status = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        root.addView(status)

        coords = TextView(this)
        root.addView(coords)

        healNote = TextView(this).apply { setTextColor(Color.rgb(150, 60, 0)) }
        root.addView(healNote)

        setContentView(root)

        mockBtn.setOnClickListener {
            if (MockService.running) {
                MockService.stop(this)
                Toast.makeText(this, "Mock stopped", Toast.LENGTH_SHORT).show()
            } else {
                val lat = if (MockService.curLat != 0.0) MockService.curLat else 22.3193
                val lon = if (MockService.curLon != 0.0) MockService.curLon else 114.1694
                MockService.startPersistent(this, lat, lon)
                Toast.makeText(this, "Mock started (persistent)", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

        jogBtn.setOnClickListener {
            if (JogService.running) {
                val i = Intent(this, JogService::class.java)
                i.putExtra("cmd", "stop")
                startService(i)
                Toast.makeText(this, "Jog stopped", Toast.LENGTH_SHORT).show()
            } else {
                val lat = if (JogService.curLat != 0.0) JogService.curLat
                          else if (MockService.curLat != 0.0) MockService.curLat else null
                val lon = if (JogService.curLon != 0.0) JogService.curLon
                          else if (MockService.curLon != 0.0) MockService.curLon else null
                if (lat == null || lon == null) {
                    Toast.makeText(this, "No last location — run Mock first or use adb", Toast.LENGTH_LONG).show()
                } else {
                    val i = Intent(this, JogService::class.java)
                    i.putExtra("mode", "loop")
                    i.putExtra("speed_kph", 10.0f)
                    i.putExtra("steps_per_sec", 2.0f)
                    i.putExtra("radius_m", 100)
                    i.putExtra("duration_min", 30)
                    i.putExtra("lat", lat.toFloat())
                    i.putExtra("lon", lon.toFloat())
                    startForegroundService(i)
                    Toast.makeText(this, "Jog started (30min loop)", Toast.LENGTH_SHORT).show()
                }
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
        MockService.start(this, lat, lon)
        Toast.makeText(this, String.format(Locale.US, "Pinning %.6f, %.6f", lat, lon), Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        mockBtn.text = "Mock: ${if (MockService.running) "ON" else "off"}"
        jogBtn.text = "Jog: ${if (JogService.running) "ON" else "off"}"

        status.text = buildString {
            append("Mock ticks: ${MockService.tickCount}")
            append("  ·  Jog: ${"%.0f".format(JogService.totalMeters)}m / ${JogService.totalSteps} steps")
            if (JogService.lastError.isNotBlank()) append("\nJog err: ${JogService.lastError}")
        }

        coords.text = String.format(Locale.US,
            "Mock @ %.6f, %.6f\nJog  @ %.6f, %.6f",
            MockService.curLat, MockService.curLon, JogService.curLat, JogService.curLon)

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
