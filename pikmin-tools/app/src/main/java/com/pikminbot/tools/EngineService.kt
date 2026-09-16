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
import java.util.Locale

/**
 * Unified PikminBot engine service (v2.0).
 *
 * Replaces the v1 sibling pair MockService + JogService with ONE foreground
 * service owning the single GPS mock provider. This removes the intra-app
 * conflict those two caused (both injected on the shared provider ->
 * last-writer-wins jumping + provider remove/add thrash + double wakelocks).
 *
 * One service, one injection loop, one test provider, one wakelock. Mode is a
 * live-switchable property (pin <-> jog) — you never tear the service down to
 * change behaviour.
 *
 * Adb drive:
 *   am start-foreground-service -n com.pikminbot.tools/.EngineService \
 *       --es mode pin --ef lat L --ef lon L [--ez persistent true] [--el timeout MS]
 *   am start-foreground-service -n com.pikminbot.tools/.EngineService \
 *       --es mode jog --es jogmode loop|straight --ef speed_kph 8.0 --ei heading 90 \
 *       --ef steps_per_sec 2.5 --ei radius_m 100 --ei duration_min 30 \
 *       [--ef lat L --ef lon L]
 *   am start-foreground-service -n com.pikminbot.tools/.EngineService --es cmd stop
 *
 * Behaviour + defaults ported verbatim from com.pikminbot.mockloc MockService
 * (v1.10) and com.pikminbot.jogger JogService (v1.2), with the SelfHeal chain
 * integrated for MOCK_LOCATION appop recovery.
 */
class EngineService : Service() {

    private val TAG = "PikminBotTools"

    companion object {
        // Live status surfaced to the UI / adb.
        @JvmStatic var running = false
        @JvmStatic var mode = "pin"          // "pin" | "jog"
        @JvmStatic var curLat = 22.3193
        @JvmStatic var curLon = 114.1694
        @JvmStatic var tickCount = 0
        @JvmStatic var lastTick = 0L
        @JvmStatic var startEpochMs = 0L
        @JvmStatic var totalMeters = 0.0
        @JvmStatic var totalSteps = 0L
        @JvmStatic var lastError = ""
        @JvmStatic var pinPersistent = false

        /** True while injection is refused (mock slot lost) but the service keeps
         *  running so it can resume by itself once the slot is restored. */
        @JvmStatic var degraded = false

        // Last explicit request, replayed by rearm() after a repair so the user
        // never has to re-enter a pin or jog after a Developer options trip.
        @JvmStatic var lastMode = "pin"
        @JvmStatic var lastLat = 0.0
        @JvmStatic var lastLon = 0.0
        @JvmStatic var lastPersistent = true

        /** Replay the last pin/jog request (used by RepairActivity + self-heal). */
        @JvmStatic fun rearm(ctx: Context) {
            try {
                if (lastMode == "jog") {
                    startJog(ctx, lastLat, lastLon, jogMode, jogSpeedKph, jogStepsPerSec,
                        jogRadiusM, jogHeadingDeg, jogDurationMin)
                } else if (lastLat != 0.0 || lastLon != 0.0) {
                    startPin(ctx, lastLat, lastLon, lastPersistent)
                }
            } catch (t: Throwable) {
                Log.e("PikminBotTools", "rearm failed", t)
            }
        }

        // Jog parameters (persist across runs).
        @JvmStatic var jogSpeedKph = 10.0
        @JvmStatic var jogStepsPerSec = 2.0
        @JvmStatic var jogDurationMin = 30L
        @JvmStatic var jogRadiusM = 100.0
        @JvmStatic var jogHeadingDeg = 0.0
        @JvmStatic var jogMode = "loop"      // "loop" | "straight"

        private const val NOTIF_ID = 1
        private const val TICK_MS = 900L
        private const val STEP_FLUSH_SEC = 30L
        private const val DEFAULT_LIFETIME_MS = 90_000L

        fun startPin(ctx: Context, lat: Double, lon: Double, persistent: Boolean) {
            val i = Intent(ctx, EngineService::class.java)
            i.putExtra("mode", "pin")
            i.putExtra("lat", lat)
            i.putExtra("lon", lon)
            i.putExtra("persistent", persistent)
            ctx.startForegroundService(i)
        }

        /** Read-only jog advance speed in m/s, for the UI status line. */
        @JvmStatic fun jogSpeedMpsView(): Double = jogSpeedKph * 1000.0 / 3600.0

        fun startJog(ctx: Context, lat: Double, lon: Double, modeStr: String,
                     kph: Double, sps: Double, radius: Double, heading: Double,
                     durMin: Long) {
            val i = Intent(ctx, EngineService::class.java)
            i.putExtra("mode", "jog")
            i.putExtra("jogmode", modeStr)
            i.putExtra("lat", lat)
            i.putExtra("lon", lon)
            i.putExtra("speed_kph", kph)
            i.putExtra("steps_per_sec", sps)
            i.putExtra("radius_m", radius)
            i.putExtra("heading", heading)
            i.putExtra("duration_min", durMin)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, EngineService::class.java)
            i.putExtra("cmd", "stop")
            ctx.startService(i)
            ctx.stopService(Intent(ctx, EngineService::class.java))
        }
    }

    private lateinit var lm: LocationManager
    private var wl: PowerManager.WakeLock? = null
    private var loop: Handler? = null
    private var lifetimeMs = DEFAULT_LIFETIME_MS

    private var lastFlushMs = 0L
    private var lastDegradedNotifMs = 0L
    private var stepsSinceFlush = 0.0
    private var lastStepMs = 0L

    // jog geometry (loop mode)
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var angle = 0.0

    private val expiryTask = Runnable {
        Log.i(TAG, "pin lifetime reached, releasing mock")
        stopSelf()
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            loop?.postDelayed(this, TICK_MS)
        }
    }

    private val slotListener: (String) -> Unit = { ev ->
        Log.i(TAG, "slot event: $ev")
        if (ev.startsWith("slot-lost") || ev.startsWith("dev-mode") || ev.startsWith("selection=")) {
            // A developer-mode toggle / selection change may or may not have cost
            // us the slot — probe and repair either way (throttled inside SelfHeal).
            if (!SelfHeal.slotOk(this)) {
                val fixed = SelfHeal.attemptRepair(this, "SlotWatch:$ev")
                if (!fixed) markDegraded() else clearDegraded()
            } else {
                clearDegraded()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        SlotEvents.subscribe(slotListener)
        SlotWatch.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("cmd") == "stop") {
            Log.i(TAG, "stop command received")
            stopSelf()
            return START_NOT_STICKY
        }

        // Parse the target mode from this command (default: stay in current, or pin if idle).
        val wantMode = when {
            intent?.getStringExtra("mode") == null -> mode   // update-in-place
            else -> if (intent.getStringExtra("mode").equals("jog", true)) "jog" else "pin"
        }

        // Optional params.
        val lat = readCoord(intent, "lat", "latitude")
        val lon = readCoord(intent, "lon", "lng", "longitude")
        if (intent?.hasExtra("timeout") == true)
            lifetimeMs = intent.getLongExtra("timeout", DEFAULT_LIFETIME_MS)
        // Persistence is per-request. A sticky `true` used to make every later
        // pin immortal (expiry never re-armed) — absent extra = not persistent.
        if (intent?.hasExtra("persistent") == true)
            pinPersistent = intent.getBooleanExtra("persistent", false)
        else if (!running)
            pinPersistent = false
        if (intent?.hasExtra("speed_kph") == true) {
            val s = readCoord(intent, "speed_kph")
            if (!s.isNaN()) jogSpeedKph = s.coerceIn(0.5, 30.0)
        }
        if (intent?.hasExtra("steps_per_sec") == true) {
            val sp = readCoord(intent, "steps_per_sec")
            if (!sp.isNaN()) jogStepsPerSec = sp.coerceIn(0.0, 10.0)
        }
        if (intent?.hasExtra("radius_m") == true)
            jogRadiusM = intent.getIntExtra("radius_m", jogRadiusM.toInt()).toDouble().coerceIn(20.0, 5000.0)
        if (intent?.hasExtra("heading") == true) {
            val h = readCoord(intent, "heading")
            if (!h.isNaN()) jogHeadingDeg = h % 360.0
        }
        if (intent?.hasExtra("duration_min") == true)
            jogDurationMin = intent.getIntExtra("duration_min", jogDurationMin.toInt())
                .toLong().coerceIn(1L, 240L)
        if (intent?.getStringExtra("jogmode") != null)
            jogMode = if (intent.getStringExtra("jogmode").equals("straight", true)) "straight" else "loop"

        // Validate supplied coords up front.
        if (!lat.isNaN() && !lon.isNaN()
                && !(lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0)) {
            lastError = String.format(Locale.US, "invalid coords: %.6f, %.6f", lat, lon)
            Log.w(TAG, lastError)
            stopSelf()
            return START_NOT_STICKY
        }

        // ---------- live update while running ----------
        if (running) {
            // New coordinates relocate the entity (pin keep point / jog re-centre).
            if (!lat.isNaN() && !lon.isNaN()) {
                curLat = lat
                curLon = lon
                lastLat = lat
                lastLon = lon
                lastMode = wantMode
                if (mode == "jog" && jogMode == "loop") {
                    centerLat = lat
                    centerLon = lon
                    angle = Math.toRadians(jogHeadingDeg)
                }
            }
            // Mode switch?
            if (wantMode != mode) {
                switchMode(wantMode)
            } else if (mode == "jog") {
                // Same jog: refresh route centre if we relocated above AND reset the
                // straight-line origin back to the new point is handled in switchMode.
                // (loop centre already updated above; straight just carries on.)
            }
            // Re-arm expiry when pinning non-persistently.
            loop?.removeCallbacks(expiryTask)
            if (mode == "pin" && !pinPersistent) loop?.postDelayed(expiryTask, lifetimeMs)
            if (loop == null) startLoop()
            Log.i(TAG, String.format(Locale.US, "engine update: mode=%s @ %.6f, %.6f",
                mode, curLat, curLon))
            // Refresh the live notification text.
            callSuperNotificationRefresh()
            return START_STICKY
        }

        // ---------- fresh start ----------
        // Resolve a starting position: explicit > last-known location.
        var slat = lat
        var slon = lon
        if (slat.isNaN() || slon.isNaN()) {
            val last = lastKnownLocation()
            if (last != null) { slat = last.latitude; slon = last.longitude }
        }
        if (slat.isNaN() || slon.isNaN()) {
            lastError = "no location: open the app or pass -ef lat/-ef lon"
            Log.w(TAG, lastError)
            stopSelf()
            return START_NOT_STICKY
        }
        curLat = slat
        curLon = slon

        mode = wantMode
        lastMode = mode
        lastLat = slat
        lastLon = slon
        lastPersistent = pinPersistent
        startEpochMs = System.currentTimeMillis()
        totalMeters = 0.0
        totalSteps = 0L
        lastError = ""
        lastFlushMs = startEpochMs
        stepsSinceFlush = 0.0
        lastStepMs = startEpochMs

        if (mode == "jog") {
            centerLat = slat
            centerLon = slon
            angle = Math.toRadians(jogHeadingDeg)
        }

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        inject()
        running = true
        loop = Handler(Looper.getMainLooper())
        loop?.postDelayed(tick, TICK_MS)
        if (mode == "pin" && !pinPersistent) loop?.postDelayed(expiryTask, lifetimeMs)
        Log.i(TAG, String.format(Locale.US, "%s @ %.6f, %.6f%s",
            mode, curLat, curLon,
            if (mode == "pin" && !pinPersistent) " (lifetime ${lifetimeMs}ms)" else ""))
        return START_STICKY
    }

    /** (Re)start the tick loop if it isn't running. */
    private fun startLoop() {
        if (loop == null) {
            loop = Handler(Looper.getMainLooper())
            loop?.postDelayed(tick, TICK_MS)
        }
    }

    /** Live switch pin <-> jog without tearing the service down. */
    private fun switchMode(newMode: String) {
        // Bank the jog steps still sitting in the <30 s window before the counters
        // are reset — switching modes used to discard up to 30 s of steps.
        if (mode == "jog" && stepsSinceFlush > 0.0) {
            try { flushSteps(Instant.ofEpochMilli(lastFlushMs), Instant.now()) } catch (t: Throwable) {}
        }
        mode = newMode
        startEpochMs = System.currentTimeMillis()
        totalMeters = 0.0
        totalSteps = 0L
        lastError = ""
        if (mode == "jog") {
            // Re-base the loop centre on the current position.
            centerLat = curLat
            centerLon = curLon
            angle = Math.toRadians(jogHeadingDeg)
            lastStepMs = System.currentTimeMillis()
            lastFlushMs = lastStepMs
            stepsSinceFlush = 0.0
        }
        // A pin transition while already running keeps the same provider loop ticking.
        Log.i(TAG, String.format(Locale.US, "mode switched -> %s", mode))
    }

    /** Push an updated notification (title/body reflect live params). */
    private fun callSuperNotificationRefresh() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Log.e(TAG, "notification refresh failed", t)
        }
    }

    /** One engine tick. */
    private fun step() {
        val now = System.currentTimeMillis()
        if (mode == "jog") {
            if (now - startEpochMs >= jogDurationMin * 60_000L) {
                Log.i(TAG, String.format(Locale.US, "jog complete: %dmin, %.0fm, %d steps",
                    jogDurationMin, totalMeters, totalSteps))
                stopSelf()
                return
            }
            jogTick(now)
        } else {
            // Pin: keep the fix fresh (a single injection goes stale in ~10-20s).
            inject()
            lastTick = now
            tickCount++
            callSuperNotificationRefresh()
        }
    }

    private fun speedMps(): Double = jogSpeedKph * 1000.0 / 3600.0

    /** One jog tick: advance by real elapsed dt, inject, bank steps. */
    private fun jogTick(now: Long) {
        val dt = ((now - lastStepMs) / 1000.0).coerceIn(0.0, 10.0)
        lastStepMs = now
        if (dt <= 0.0) return
        if (jogMode == "straight") {
            val h = Math.toRadians(jogHeadingDeg)
            curLat += speedMps() * dt * Math.cos(h) / 111320.0
            curLon += speedMps() * dt * Math.sin(h) / (111320.0 * Math.cos(Math.toRadians(curLat)))
        } else {
            angle += speedMps() * dt / jogRadiusM
            curLat = centerLat + jogRadiusM * Math.cos(angle) / 111320.0
            curLon = centerLon + jogRadiusM * Math.sin(angle) / (111320.0 * Math.cos(Math.toRadians(centerLat)))
        }
        inject()
        totalMeters += speedMps() * dt
        val steps = jogStepsPerSec * dt
        totalSteps += steps.toLong()
        stepsSinceFlush += steps
        if (now - lastFlushMs >= STEP_FLUSH_SEC * 1000L) {
            flushSteps(Instant.ofEpochMilli(lastFlushMs), Instant.ofEpochMilli(now))
            lastFlushMs = now
        }
        callSuperNotificationRefresh()
    }

    /** Register the mock provider if needed and push the current fix. */
    private fun inject() {
        try {
            try {
                lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false,
                    false, true, true, true, 3, 2)
            } catch (e: IllegalArgumentException) {
                // already added
            } catch (e: SecurityException) {
                lastError = "MOCK_LOCATION denied — mock location slot lost"
                // Log once per degradation, not once per 900 ms tick.
                if (!degraded) Log.e(TAG, lastError)
                SelfHeal.noteSecurityException()
                // Repair chain (root -> Shizuku -> setting -> guided screen).
                // The service deliberately KEEPS RUNNING when the slot cannot be
                // restored: the tick loop retries every 900 ms, so the moment the
                // user re-selects the app in Developer options injection resumes by
                // itself with no re-tap. (v2.2 stopped the service here, which
                // looked like "the app died" after a developer-mode toggle.)
                if (!SelfHeal.attemptRepair(this, "EngineService.addTestProvider")) {
                    markDegraded()
                    return
                }
                try {
                    lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false,
                        false, true, true, true, 3, 2)
                    Log.i(TAG, "self-heal retry succeeded")
                    clearDegraded()
                } catch (t: Throwable) {
                    Log.e(TAG, "self-heal retry failed", t)
                    markDegraded()
                    return
                }
            }
            try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true) } catch (t: Throwable) {}
            val loc = Location(LocationManager.GPS_PROVIDER)
            loc.latitude = curLat
            loc.longitude = curLon
            loc.accuracy = 3.0f
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            lastTick = System.currentTimeMillis()
            tickCount++
            if (degraded) clearDegraded()
        } catch (t: Throwable) {
            Log.e(TAG, "inject failed", t)
            lastError = t.message ?: "inject failed"
        }
    }

    /** Slot lost: keep serving, surface a repair notification, resume later. */
    private fun markDegraded() {
        if (!degraded) {
            degraded = true
            Log.w(TAG, "engine degraded: mock location slot not ours (${SelfHeal.describe(this)})")
        }
        lastError = "mock location slot lost — repair needed"
        // Post once on transition, then at most once a minute. The tick loop
        // retries every 900 ms, so an unguarded post would spam the shade.
        val now = System.currentTimeMillis()
        if (lastDegradedNotifMs == 0L || now - lastDegradedNotifMs > 60_000L) {
            lastDegradedNotifMs = now
            SelfHeal.postFixNotification(this, "EngineService")
        }
        callSuperNotificationRefresh()
    }

    private fun clearDegraded() {
        if (degraded) {
            degraded = false
            lastError = ""
            Log.i(TAG, "engine recovered: slot restored, injection resumed")
            SelfHeal.clearFixNotification(this)
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
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pikminbottools:engine")
                wl?.acquire(0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wakelock failed", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("engine", "PikminBot Engine", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        if (degraded) {
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, "engine") else Notification.Builder(this)
            val pi = android.app.PendingIntent.getActivity(
                this, 7,
                android.content.Intent(this, RepairActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            return b.setContentTitle("Mock location lost — tap to repair")
                .setContentText("Developer options changed. Engine is holding, will resume itself.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
        }
        val title: String
        val text: String
        if (mode == "jog") {
            title = String.format(Locale.US, "Jogging %.1f km/h · %.1f steps/s", jogSpeedKph, jogStepsPerSec)
            text = String.format(Locale.US, "%.5f, %.5f · %d steps", curLat, curLon, totalSteps)
        } else {
            title = "PikminBot mock active"
            text = String.format(Locale.US, "Pinning %.5f, %.5f", curLat, curLon)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "engine") else Notification.Builder(this)
        return b
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        SlotEvents.unsubscribe(slotListener)
        SlotWatch.stop()
        loop?.removeCallbacks(tick)
        loop?.removeCallbacks(expiryTask)
        loop = null
        if (stepsSinceFlush > 0.0) {
            try { flushSteps(Instant.ofEpochMilli(lastFlushMs), Instant.now()) } catch (t: Throwable) {}
        }
        if (wl != null && wl?.isHeld == true) { try { wl?.release() } catch (t: Throwable) {} }
        wl = null
        try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (t: Throwable) {}
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (t: Throwable) {}
        handBackToRealLocation()
        stopForeground(true)
        Log.i(TAG, String.format(Locale.US, "engine stopped: %.0fm, %d steps", totalMeters, totalSteps))
        super.onDestroy()
    }

    /** Force a real (non-mock) fix after the mock provider is removed.
     *  Without this, `fused` keeps serving the last mock fix — still flagged
     *  `mock` — until some other app happens to ask for a real one, which
     *  leaves the game holding a stale mocked position. */
    private fun handBackToRealLocation() {
        try {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            for (p in arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                try { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) } catch (t: Throwable) {}
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try { lm.removeUpdates(listener) } catch (t: Throwable) {}
            }, 10_000L)
            Log.i(TAG, "real-fix handback requested")
        } catch (t: Throwable) {
            Log.e(TAG, "handback failed", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
