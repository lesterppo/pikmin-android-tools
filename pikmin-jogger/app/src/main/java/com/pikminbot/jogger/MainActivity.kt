package com.pikminbot.jogger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PikminBot Jogger — simulate jogging for Pikmin Bloom:
 *  - mock GPS moves at 10 km/h (loop or straight line)
 *  - 2 steps/second written to Health Connect
 *
 * Interactive: open from the launcher.
 * Headless (adb):
 *   am start -n com.pikminbot.jogger/.MainActivity --ez start true [--es mode loop] [--ei radius_m 100] [--ei duration_min 30] [--ef lat X --ef lon Y]
 *   am start -n com.pikminbot.jogger/.MainActivity --es cmd stop
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var modeLabel: TextView
    private lateinit var loopBtn: Button
    private lateinit var straightBtn: Button
    private lateinit var radiusInput: EditText
    private lateinit var headingInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var stepsInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var startBtn: Button

    private val poll = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            refreshStatus()
            poll.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleHeadless(intent)) return
        setContentView(buildUi())
        refreshStatus()
        requestLocationPerms()
        if (!StepWriter.hasPermissions(this)) StepWriter.requestPermissions(this)
        poll.postDelayed(pollTask, 500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHeadless(intent)
    }

    /** Handles headless commands; returns true when the activity should finish. */
    private fun handleHeadless(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        if (extras.getBoolean("start", false)) {
            startJog(
                mode = extras.getString("mode") ?: "loop",
                radiusM = extras.getInt("radius_m", 100),
                heading = extras.getInt("heading", 0),
                speedKph = readDouble(extras, "speed_kph", 10.0),
                stepsPerSec = readDouble(extras, "steps_per_sec", 2.0),
                durationMin = extras.getInt("duration_min", 30),
                lat = readDouble(extras, "lat", Double.NaN),
                lon = readDouble(extras, "lon", Double.NaN),
            )
            finish()
            return true
        }
        if ("stop".equals(extras.getString("cmd"))) {
            startService(Intent(this, JogService::class.java).putExtra("cmd", "stop"))
            finish()
            return true
        }
        if (extras.getBoolean("verify", false)) {
            val r = StepWriter.verify(this, todayStart(), Instant.now().plusSeconds(60))
            val total = r.sumOf { it.steps }
            Log.i("PikminJogger", "VERIFY: ${r.size} own records, $total steps")
            Toast.makeText(this, "Verify: $total steps in ${r.size} records", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        if (extras.getBoolean("delete", false)) {
            val n = StepWriter.deleteMine(this, todayStart(), Instant.now().plusSeconds(60))
            Log.i("PikminJogger", "DELETE: removed $n own records")
            Toast.makeText(this, "Deleted $n records", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == StepWriter.REQ_PERMISSIONS) {
            refreshStatus()
            appendLog("HC permission screen closed (granted=${StepWriter.hasPermissions(this)})")
        }
    }

    override fun onDestroy() {
        poll.removeCallbacks(pollTask)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi(): ViewGroup {
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        fun label(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, dp(12), 0, dp(2))
        }

        col.addView(TextView(this).apply {
            text = "PikminBot Jogger"
            textSize = 20f
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "10 km/h · 2 steps/s"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        statusView = TextView(this).apply { textSize = 13f }
        col.addView(statusView)

        col.addView(label("Route"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loopBtn = Button(this).apply {
            text = "Loop"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { setMode("loop") }
        }
        straightBtn = Button(this).apply {
            text = "Straight"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { setMode("straight") }
        }
        modeRow.addView(loopBtn)
        modeRow.addView(straightBtn)
        col.addView(modeRow)
        modeLabel = TextView(this).apply { textSize = 12f }
        col.addView(modeLabel)

        col.addView(label("Speed (km/h)"))
        speedInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10.0")
        }
        col.addView(speedInput)

        col.addView(label("Steps per second"))
        stepsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("2.0")
        }
        col.addView(stepsInput)

        col.addView(label("Loop radius (m)"))
        radiusInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("100")
        }
        col.addView(radiusInput)

        col.addView(label("Heading (deg, 0-360: 0=N 90=E 180=S 270=W)"))
        headingInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        col.addView(headingInput)

        col.addView(label("Duration (minutes)"))
        durationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("30")
        }
        col.addView(durationInput)

        startBtn = Button(this).apply { setOnClickListener { onStartStop() } }
        col.addView(startBtn)

        col.addView(Button(this).apply {
            text = "Request permissions"
            setOnClickListener {
                requestLocationPerms()
                StepWriter.requestPermissions(this@MainActivity)
            }
        })
        col.addView(Button(this).apply {
            text = "Verify my HC records"
            setOnClickListener {
                val r = StepWriter.verify(this@MainActivity, todayStart(), Instant.now().plusSeconds(60))
                val total = r.sumOf { it.steps }
                appendLog("Own HC records: ${r.size}, $total steps (last 24h)")
            }
        })

        col.addView(label("Log"))
        logView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        col.addView(logView)
        return root
    }

    private fun setMode(m: String) {
        JogService.mode = m
        refreshStatus()
    }

    private fun onStartStop() {
        if (JogService.running) {
            startService(Intent(this, JogService::class.java).putExtra("cmd", "stop"))
            appendLog("Stopping...")
            return
        }
        val speed = speedInput.text.toString().toDoubleOrNull() ?: 10.0
        val steps = stepsInput.text.toString().toDoubleOrNull() ?: 2.0
        val radius = radiusInput.text.toString().toIntOrNull() ?: 100
        val heading = headingInput.text.toString().toIntOrNull() ?: 0
        val duration = durationInput.text.toString().toIntOrNull() ?: 30
        if (heading !in 0..360) {
            Toast.makeText(this, "Heading must be 0-360 degrees", Toast.LENGTH_LONG).show()
            return
        }
        if (speed <= 0.0 || speed > 30.0) {
            Toast.makeText(this, "Speed must be 0.1-30 km/h", Toast.LENGTH_LONG).show()
            return
        }
        if (steps < 0.0 || steps > 10.0) {
            Toast.makeText(this, "Steps/s must be 0-10", Toast.LENGTH_LONG).show()
            return
        }
        startJog(JogService.mode, radius, heading, speed, steps, duration, Double.NaN, Double.NaN)
    }

    private fun startJog(mode: String, radiusM: Int, heading: Int, speedKph: Double, stepsPerSec: Double,
                         durationMin: Int, lat: Double, lon: Double) {
        val i = Intent(this, JogService::class.java)
            .putExtra("mode", mode)
            .putExtra("radius_m", radiusM)
            .putExtra("heading", heading)
            .putExtra("speed_kph", speedKph)
            .putExtra("steps_per_sec", stepsPerSec)
            .putExtra("duration_min", durationMin)
        if (!lat.isNaN()) i.putExtra("lat", lat)
        if (!lon.isNaN()) i.putExtra("lon", lon)
        startForegroundService(i)
        appendLog("Starting jog: $mode ${"%.1f".format(speedKph)}km/h heading=${heading}° ${"%.1f".format(stepsPerSec)}/s radius=$radiusM m dur=${durationMin}min")
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val j = JogService
        val hc = StepWriter.hasPermissions(this)
        if (j.running) {
            val elapsedSec = (System.currentTimeMillis() - j.startEpochMs) / 1000
            val speed = if (elapsedSec > 0) j.totalMeters / elapsedSec else 0.0
            statusView.text = "JOGGING · ${"%.2f".format(speed)} m/s (${"%.1f".format(j.speedKph)} km/h) · ${"%.1f".format(j.stepsPerSec)} steps/s\n" +
                    "Pos: ${"%.5f".format(j.curLat)}, ${"%.5f".format(j.curLon)} heading ${j.headingDeg.toInt()}°\n" +
                    "Elapsed ${elapsedSec / 60}m${elapsedSec % 60}s · ${"%.0f".format(j.totalMeters)} m · ${j.totalSteps} steps\n" +
                    "HC WRITE_STEPS: $hc"
            startBtn.text = "■ Stop"
            startBtn.isEnabled = true
        } else {
            statusView.text = "Idle — not jogging.\nHC WRITE_STEPS granted: $hc\n" +
                    (if (j.lastError.isNotBlank()) "Last error: ${j.lastError}" else "")
            startBtn.text = "▶ Start jog"
            startBtn.isEnabled = true
        }
        if (::modeLabel.isInitialized) {
            modeLabel.text = "Mode: ${j.mode} · radius ${j.radiusM.toInt()} m · heading ${j.headingDeg.toInt()}°"
            loopBtn.isSelected = j.mode == "loop"
            straightBtn.isSelected = j.mode == "straight"
        }
    }

    /** Type-checked extra reader: Bundle getters silently return the default on a wrong type. */
    private fun readDouble(extras: android.os.Bundle, key: String, default: Double): Double {
        if (!extras.containsKey(key)) return default
        val v: Any? = try { extras.get(key) } catch (t: Throwable) { null }
        if (v is Number) return v.toDouble()
        return default
    }

    private fun requestLocationPerms() {
        try {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ), 1)
        } catch (t: Throwable) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendLog("Location permissions granted")
        }
    }

    private fun todayStart(): Instant =
        LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).atZone(ZoneId.systemDefault()).toInstant()

    private fun appendLog(s: String) {
        if (!::logView.isInitialized) return
        logView.text = logView.text.toString().let { if (it.isBlank()) s else it + "\n" + s }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
