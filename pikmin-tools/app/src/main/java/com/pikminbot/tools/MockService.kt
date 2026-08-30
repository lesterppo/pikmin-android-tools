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

/**
 * Foreground service that keeps a mock GPS fix pinned by re-injecting it
 * roughly every second. This is what makes injection survive on Android 12+:
 * a single setTestProviderLocation goes STALE after ~10-20s, so we must
 * refresh it from inside the phone (no computer needed).
 *
 * Behaviour ported verbatim from com.pikminbot.mockloc MockService (v1.10),
 * with the SelfHeal chain integrated for MOCK_LOCATION appop recovery.
 */
class MockService : Service() {

    private val TAG = "PikminBotTools"
    private val NOTIF_ID = 1
    private val INTERVAL_MS = 900L
    // Natural lifetime: keep the mock fresh for this long (re-pinning so it
    // stays visible to apps), then release so the device reverts to real GPS.
    // This is the behaviour that historically let Pikmin Bloom accept actions.
    private val DEFAULT_LIFETIME_MS = 90_000L

    companion object {
        // Live status surfaced to MainActivity polling.
        @JvmStatic var running = false
        @JvmStatic var lastTick = 0L
        @JvmStatic var tickCount = 0
        @JvmStatic var curLat = 0.0
        @JvmStatic var curLon = 0.0

        /** Convenience for external starters (adb broadcast receiver / UI):
         *  keep the mock fresh for the natural lifetime (default 90s), then release
         *  so the device reverts to real GPS. */
        fun start(ctx: Context, lat: Double, lon: Double) {
            val i = Intent(ctx, MockService::class.java)
            i.putExtra("lat", lat)
            i.putExtra("lon", lon)
            ctx.startForegroundService(i)
        }

        /** Persistent map-coverage mode: keep re-pinning every ~1s forever. */
        fun startPersistent(ctx: Context, lat: Double, lon: Double) {
            val i = Intent(ctx, MockService::class.java)
            i.putExtra("lat", lat)
            i.putExtra("lon", lon)
            i.putExtra("persistent", true)
            ctx.startForegroundService(i)
        }

        /** Stop + clear the mock from any context. */
        fun stop(ctx: Context) {
            val i = Intent(ctx, MockService::class.java)
            i.putExtra("cmd", "stop")
            ctx.startService(i)   // delivers cmd=stop to onStartCommand -> stopSelf()
            ctx.stopService(Intent(ctx, MockService::class.java))
        }
    }

    private var lm: LocationManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var loop: Handler? = null
    private var lifetimeMs = DEFAULT_LIFETIME_MS
    private val expiryTask = Runnable {
        Log.i(TAG, "natural lifetime reached (${lifetimeMs}ms), releasing mock")
        stopSelf()
    }
    private val tick = object : Runnable {
        override fun run() {
            inject()
            loop?.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit stop (from toggle OFF / CLEAR broadcast): tear down now.
        if (intent?.getStringExtra("cmd") == "stop") {
            Log.i(TAG, "stop command received")
            stopSelf()
            return START_NOT_STICKY
        }

        // Default: re-pin every ~1s so the mock stays VISIBLE to apps (a single
        // fix goes stale in ~10-20s on Android 12+), then release after the
        // natural lifetime so the device reverts to real GPS.
        // persistent=true keeps the mock forever (map-coverage mode).
        val persistent = intent?.getBooleanExtra("persistent", false) == true
        if (intent?.hasExtra("timeout") == true) {
            lifetimeMs = intent.getLongExtra("timeout", DEFAULT_LIFETIME_MS)
        }

        val lat = readCoord(intent, "lat", "latitude")
        val lon = readCoord(intent, "lon", "lng", "longitude")

        // Reject out-of-range coords up front: the system throws
        // BadLocationException on lon < -180 (or > 180), and a far-away
        // "valid" point is ignored by the game anyway. Never inject garbage.
        if (!lat.isNaN() && !lon.isNaN()
                && !(lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0)) {
            Log.w(TAG, String.format("invalid coords rejected: %.6f, %.6f", lat, lon))
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) {
            // Already alive: update the target; the active loop picks it up.
            if (!lat.isNaN()) curLat = lat
            if (!lon.isNaN()) curLon = lon
            if (loop == null) startLoop()
            if (!persistent && loop != null) {
                loop?.removeCallbacks(expiryTask)
                loop?.postDelayed(expiryTask, lifetimeMs)
            }
            Log.i(TAG, String.format("target updated %.6f, %.6f", curLat, curLon))
            return START_STICKY
        }

        if (lat.isNaN() || lon.isNaN()) {
            Log.w(TAG, "no lat/lon supplied, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        curLat = lat
        curLon = lon

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        inject() // immediate first shot
        startLoop()
        if (!persistent) loop?.postDelayed(expiryTask, lifetimeMs)
        running = true
        Log.i(TAG, String.format("service started, pinning %.6f, %.6f (lifetime %dms%s)",
                curLat, curLon, if (persistent) -1 else lifetimeMs, if (persistent) ", persistent" else ""))
        return START_STICKY
    }

    private fun startLoop() {
        loop = Handler(Looper.getMainLooper())
        loop?.postDelayed(tick, INTERVAL_MS)
    }

    private fun inject() {
        val lm = lm ?: return
        try {
            try {
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    3, 2)
            } catch (e: IllegalArgumentException) {
                // already added
            } catch (e: SecurityException) {
                Log.e(TAG, "addTestProvider SecurityException: ${e.message}")
                SelfHeal.attemptRepair(this, "MockService.addTestProvider")
            }
            try {
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            } catch (t: Throwable) {}
            val loc = Location(LocationManager.GPS_PROVIDER)
            loc.latitude = curLat
            loc.longitude = curLon
            loc.accuracy = 3.0f
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            lastTick = System.currentTimeMillis()
            tickCount++
        } catch (t: Throwable) {
            Log.e(TAG, "inject failed", t)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm != null && wl == null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pikminbottools:inject")
                wl?.acquire(0) // until released in onDestroy
            }
        } catch (t: Throwable) {
            Log.e(TAG, "wakelock failed", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("mockloc", "MockLoc GPS", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "mockloc") else Notification.Builder(this)
        return b
            .setContentTitle("PikminBot MockLoc active")
            .setContentText(String.format("Pinning %.5f, %.5f", curLat, curLon))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        loop?.removeCallbacks(tick)
        loop?.removeCallbacks(expiryTask)
        loop = null
        if (wl != null && wl?.isHeld == true) {
            try { wl?.release() } catch (t: Throwable) {}
        }
        wl = null
        lm?.let {
            try { it.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (t: Throwable) {}
            try { it.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (t: Throwable) {}
        }
        stopForeground(true)
        Log.i(TAG, "service destroyed, mock cleared")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Type-checked coord reader: Bundle getters silently return the default
     *  (NaN) for a wrong type instead of throwing, so read the raw value and
     *  convert. Handles --ef Float, --ed Double, --el Long, --ei Int, --es String. */
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
