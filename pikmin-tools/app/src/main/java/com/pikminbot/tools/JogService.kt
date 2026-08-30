package com.pikminbot.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.time.Instant

/**
 * Jogging simulator for Pikmin Bloom.
 *
 * While running it:
 *  1. Moves the mock GPS position at the configured speed (default 10 km/h)
 *     — straight line along the chosen heading (0..360 deg), or around a
 *     circular loop of the chosen radius (the heading sets the starting
 *     bearing) — re-injecting every ~1 s so the fix never goes stale.
 *  2. Writes the configured step rate (default 2 steps/s) to Health Connect,
 *     batched every 30 s, which is what Pikmin Bloom reads for step counts.
 *
 * All parameters are customizable from the UI or adb:
 *   am start-foreground-service -n com.pikminbot.tools/.JogService \
 *       --es mode loop --ef speed_kph 8.0 --ei heading 135 --ef steps_per_sec 2.5 \
 *       --ei radius_m 100 --ei duration_min 30 [--ef lat X --ef lon Y]
 *   am start-foreground-service -n com.pikminbot.tools/.JogService --es cmd stop
 *
 * Behaviour ported verbatim from com.pikminbot.jogger JogService (v1.2),
 * with the SelfHeal chain integrated for MOCK_LOCATION appop recovery.
 */
class JogService : Service() {

    private val TAG = "PikminBotTools"

    companion object {
        const val STEP_FLUSH_SEC = 30L                 // write HC every 30 s
        const val TICK_MS = 1000L
        const val MAX_DURATION_MIN = 240L
        const val MAX_SPEED_KPH = 30.0
        const val MAX_STEPS_PER_SEC = 10.0

        // Live status surfaced to the UI / adb.
        var running = false
        var curLat = 0.0
        var curLon = 0.0
        var startEpochMs = 0L
        var totalMeters = 0.0
        var totalSteps = 0L
        var lastTickMs = 0L
        var lastError = ""
        var mode = "loop"
        var radiusM = 100.0
        var headingDeg = 0.0
        var speedKph = 10.0
        var stepsPerSec = 2.0
        var durationMin = 30L
    }

    private lateinit var lm: LocationManager
    private var wl: PowerManager.WakeLock? = null
    private var loop: Handler? = null
    private var durationSec = 30L * 60L
    private var lastFlushMs = 0L
    private var stepsSinceFlush = 0.0
    private var lastStepMs = 0L
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var angle = 0.0

    private val tick = object : Runnable {
        override fun run() {
            step()
            loop?.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("cmd") == "stop") {
            Log.i(TAG, "stop command received")
            stopSelf()
            return START_NOT_STICKY
        }

        // Parse parameters (all optional; defaults persist from the last run).
        val m = intent?.getStringExtra("mode") ?: mode
        mode = if (m.equals("straight", ignoreCase = true)) "straight" else "loop"
        if (intent?.hasExtra("radius_m") == true) {
            radiusM = intent.getIntExtra("radius_m", radiusM.toInt()).toDouble().coerceIn(20.0, 5000.0)
        }
        if (intent?.hasExtra("heading") == true) {
            val h = readCoord(intent, "heading")
            if (!h.isNaN()) headingDeg = h % 360.0
        }
        if (intent?.hasExtra("speed_kph") == true) {
            val s = readCoord(intent, "speed_kph")
            if (!s.isNaN()) speedKph = s.coerceIn(0.5, MAX_SPEED_KPH)
        }
        if (intent?.hasExtra("steps_per_sec") == true) {
            val sp = readCoord(intent, "steps_per_sec")
            if (!sp.isNaN()) stepsPerSec = sp.coerceIn(0.0, MAX_STEPS_PER_SEC)
        }
        if (intent?.hasExtra("duration_min") == true) {
            durationMin = intent.getIntExtra("duration_min", durationMin.toInt())
                .toLong().coerceIn(1L, MAX_DURATION_MIN)
        }
        durationSec = durationMin * 60L
        val lat = readCoord(intent, "lat", "latitude")
        val lon = readCoord(intent, "lon", "lng", "longitude")

        if (!lat.isNaN() && !lon.isNaN()
                && !(lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0)) {
            lastError = "invalid coords: $lat, $lon"
            Log.w(TAG, lastError)
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) {
            // Update parameters on the fly.
            if (!lat.isNaN()) { centerLat = lat; curLat = lat }
            if (!lon.isNaN()) { centerLon = lon; curLon = lon }
            if (mode == "straight") { curLat = centerLat; curLon = centerLon }
            durationSec = durationMin * 60L
            startEpochMs = System.currentTimeMillis()
            Log.i(TAG, "params updated: mode=$mode speed=${"%.1f".format(speedKph)}km/h heading=${headingDeg.toInt()}deg steps=${"%.1f".format(stepsPerSec)}/s radius=${radiusM.toInt()}m dur=${durationMin}min")
            return START_STICKY
        }

        // Fresh start: resolve the starting position.
        var slat = lat
        var slon = lon
        if (slat.isNaN() || slon.isNaN()) {
            val last = lastKnownLocation()
            if (last != null) { slat = last.latitude; slon = last.longitude }
        }
        if (slat.isNaN() || slon.isNaN()) {
            lastError = "no starting location: open the app or pass -ef lat/-ef lon"
            Log.w(TAG, lastError)
            stopSelf()
            return START_NOT_STICKY
        }

        centerLat = slat
        centerLon = slon
        curLat = slat
        curLon = slon
        angle = Math.toRadians(headingDeg)
        totalMeters = 0.0
        totalSteps = 0L
        lastError = ""
        startEpochMs = System.currentTimeMillis()
        lastFlushMs = startEpochMs
        stepsSinceFlush = 0.0

        startForeground(1, buildNotification())
        acquireWakeLock()
        inject()
        running = true
        lastStepMs = System.currentTimeMillis()
        loop = Handler(Looper.getMainLooper())
        loop?.postDelayed(tick, TICK_MS)
        Log.i(TAG, "jog started: ${"%.6f".format(curLat)}, ${"%.6f".format(curLon)} mode=$mode speed=${"%.1f".format(speedKph)}km/h heading=${headingDeg.toInt()}deg steps=${"%.1f".format(stepsPerSec)}/s radius=${radiusM.toInt()}m dur=${durationMin}min")
        return START_STICKY
    }

    /** One jogging tick: advance position by REAL elapsed time, inject, bank steps. */
    private fun step() {
        val now = System.currentTimeMillis()
        if (now - startEpochMs >= durationSec * 1000L) {
            Log.i(TAG, "jog complete: ${durationMin}min, ${"%.0f".format(totalMeters)}m, $totalSteps steps")
            stopSelf()
            return
        }
        // dt in seconds since the last tick — keeps distance/steps accurate
        // even when Handler ticks drift under system load.
        val dt = ((now - lastStepMs) / 1000.0).coerceIn(0.0, 10.0)
        lastStepMs = now
        if (dt <= 0.0) return
        advancePosition(dt)
        inject()
        val meters = speedMps() * dt
        totalMeters += meters
        val steps = stepsPerSec * dt
        totalSteps += steps.toLong()
        stepsSinceFlush += steps
        if (now - lastFlushMs >= STEP_FLUSH_SEC * 1000L) {
            flushSteps(Instant.ofEpochMilli(lastFlushMs), Instant.ofEpochMilli(now))
            lastFlushMs = now
        }
        lastTickMs = now
    }

    private fun speedMps(): Double = speedKph * 1000.0 / 3600.0

    private fun advancePosition(dt: Double) {
        try {
            if (mode == "straight") {
                val h = Math.toRadians(headingDeg)
                curLat += speedMps() * dt * Math.cos(h) / 111320.0
                curLon += speedMps() * dt * Math.sin(h) / (111320.0 * Math.cos(Math.toRadians(curLat)))
            } else {
                angle += speedMps() * dt / radiusM
                curLat = centerLat + radiusM * Math.cos(angle) / 111320.0
                curLon = centerLon + radiusM * Math.sin(angle) / (111320.0 * Math.cos(Math.toRadians(centerLat)))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "advancePosition failed", t)
        }
    }

    /** Same pattern as MockService: register the GPS test provider and push a fresh fix. */
    private fun inject() {
        try {
            try {
                lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false,
                        true, true, true, 3, 2)
            } catch (e: IllegalArgumentException) {
                // already added
            } catch (e: SecurityException) {
                lastError = "MOCK_LOCATION denied: select $packageName as mock location app + grant appop"
                Log.e(TAG, lastError)
                // Self-heal instead of immediately tearing down the jog: the
                // repair chain (Shizuku / WRITE_SECURE_SETTINGS) can restore
                // the appop without restarting the service.
                SelfHeal.attemptRepair(this, "JogService.addTestProvider")
                if (SelfHeal.isMockAppSelected(this)) {
                    try {
                        lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false,
                                true, true, true, 3, 2)
                        Log.i(TAG, "self-heal retry: addTestProvider succeeded after repair")
                    } catch (t: Throwable) {
                        Log.e(TAG, "self-heal retry failed, stopping jog", t)
                        stopSelf()
                    }
                } else {
                    stopSelf()
                }
                return
            }
            try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true) } catch (t: Throwable) {}
            val loc = Location(LocationManager.GPS_PROVIDER)
            loc.latitude = curLat
            loc.longitude = curLon
            loc.accuracy = 3.0f
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (t: Throwable) {
            Log.e(TAG, "inject failed", t)
            lastError = t.message ?: "inject failed"
        }
    }

    private fun flushSteps(start: Instant, end: Instant) {
        if (stepsSinceFlush <= 0.0) return
        val n = stepsSinceFlush.toLong()
        if (n <= 0) return
        stepsSinceFlush = 0.0
        val r = StepWriter.append(this, start, end, n)
        Log.i(TAG, "HC flush $n steps: $r")
    }

    private fun lastKnownLocation(): Location? {
        for (p in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null) return l
            } catch (t: Throwable) {}
        }
        return null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wl == null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pikminbottools:run")
                wl?.acquire(0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wakelock failed", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("jogger", "PikminBot Jogger", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "jogger") else Notification.Builder(this)
        return b
            .setContentTitle(String.format("Jogging %.1f km/h · %.1f steps/s", speedKph, stepsPerSec))
            .setContentText(String.format("%.5f, %.5f · %d steps", curLat, curLon, totalSteps))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        if (loop != null) { loop?.removeCallbacks(tick); loop = null }
        if (wl != null && wl?.isHeld == true) { try { wl?.release() } catch (t: Throwable) {} }
        wl = null
        if (stepsSinceFlush > 0.0) {
            flushSteps(Instant.ofEpochMilli(lastFlushMs), Instant.now())
        }
        try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (t: Throwable) {}
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (t: Throwable) {}
        stopForeground(true)
        Log.i(TAG, "jog stopped: ${"%.0f".format(totalMeters)}m, $totalSteps steps")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Type-checked value reader — Bundle getters silently return NaN on a wrong type. */
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
}
