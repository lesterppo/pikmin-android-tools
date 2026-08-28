package com.pikminbot.mockloc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Foreground service that keeps a mock GPS fix pinned by re-injecting it
 * roughly every second. This is what makes injection survive on Android 12+:
 * a single setTestProviderLocation goes STALE after ~10-20s, so we must
 * refresh it from inside the phone (no computer needed).
 *
 * Started from MainActivity (UI) or from an adb broadcast via MockLocationReceiver.
 * Java 8 compatible (no lambdas).
 */
public class MockService extends Service {

    private static final String TAG = "PikminMockLoc";
    private static final int NOTIF_ID = 1;
    private static final long INTERVAL_MS = 900;
    // Natural lifetime: keep the mock fresh for this long (re-pinning so it
    // stays visible to apps), then release so the device reverts to real GPS.
    // This is the behaviour that historically let Pikmin Bloom accept actions.
    private static final long DEFAULT_LIFETIME_MS = 90_000L;

    // Live status surfaced to MainActivity polling.
    public static boolean running = false;
    public static long lastTick = 0;
    public static int tickCount = 0;
    public static double curLat = 0;
    public static double curLon = 0;

    private LocationManager lm;
    private PowerManager.WakeLock wl;
    private Handler loop;
    private long lifetimeMs = DEFAULT_LIFETIME_MS;
    private final Runnable expiryTask = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "natural lifetime reached (" + lifetimeMs + "ms), releasing mock");
            stopSelf();
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            inject();
            loop.postDelayed(tick, INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Explicit stop (from toggle OFF / CLEAR broadcast): tear down now.
        if (intent != null && "stop".equals(intent.getStringExtra("cmd"))) {
            Log.i(TAG, "stop command received");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Default: re-pin every ~1s so the mock stays VISIBLE to apps (a single
        // fix goes stale in ~10-20s on Android 12+), then release after the
        // natural lifetime so the device reverts to real GPS.
        // persistent=true keeps the mock forever (map-coverage mode).
        boolean persistent = intent != null && intent.getBooleanExtra("persistent", false);
        if (intent != null && intent.hasExtra("timeout")) {
            lifetimeMs = intent.getLongExtra("timeout", DEFAULT_LIFETIME_MS);
        }

        double lat = readCoord(intent, "lat", "latitude");
        double lon = readCoord(intent, "lon", "lng", "longitude");

        if (running) {
            // Already alive: update the target; the active loop picks it up.
            if (!Double.isNaN(lat)) curLat = lat;
            if (!Double.isNaN(lon)) curLon = lon;
            if (loop == null) { startLoop(); }
            if (!persistent && loop != null) { loop.removeCallbacks(expiryTask); loop.postDelayed(expiryTask, lifetimeMs); }
            Log.i(TAG, String.format("target updated %.6f, %.6f", curLat, curLon));
            return START_STICKY;
        }

        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            Log.w(TAG, "no lat/lon supplied, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        curLat = lat;
        curLon = lon;

        startForeground(NOTIF_ID, buildNotification());
        acquireWakeLock();
        inject(); // immediate first shot
        startLoop();
        if (!persistent) loop.postDelayed(expiryTask, lifetimeMs);
        running = true;
        Log.i(TAG, String.format("service started, pinning %.6f, %.6f (lifetime %dms%s)", curLat, curLon, persistent ? -1 : lifetimeMs, persistent ? ", persistent" : ""));
        return START_STICKY;
    }

    private void startLoop() {
        loop = new Handler(Looper.getMainLooper());
        loop.postDelayed(tick, INTERVAL_MS);
    }

    private void inject() {
        if (lm == null) return;
        try {
            try {
                lm.addTestProvider(
                        LocationManager.GPS_PROVIDER,
                        false, false, false, false,
                        true, true, true,
                        3, 2);
            } catch (IllegalArgumentException ignored) {
                // already added
            } catch (SecurityException e) {
                Log.e(TAG, "addTestProvider SecurityException: " + e.getMessage());
            }
            try {
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            } catch (Throwable ignored) {}
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setLatitude(curLat);
            loc.setLongitude(curLon);
            loc.setAccuracy(3.0f);
            loc.setTime(System.currentTimeMillis());
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
            lastTick = System.currentTimeMillis();
            tickCount++;
        } catch (Throwable t) {
            Log.e(TAG, "inject failed", t);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wl == null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mockloc:inject");
                wl.acquire(0); // until released in onDestroy
            }
        } catch (Throwable t) {
            Log.e(TAG, "wakelock failed", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        "mockloc", "MockLoc GPS", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, "mockloc");
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("PikminBot MockLoc active")
         .setContentText(String.format("Pinning %.5f, %.5f", curLat, curLon))
         .setSmallIcon(android.R.drawable.ic_menu_compass)
         .setOngoing(true);
        return b.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) { loop.removeCallbacks(tick); loop.removeCallbacks(expiryTask); loop = null; }
        if (wl != null && wl.isHeld()) {
            try { wl.release(); } catch (Throwable ignored) {}
        }
        if (lm != null) {
            try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false); } catch (Throwable ignored) {}
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
        }
        stopForeground(true);
        Log.i(TAG, "service destroyed, mock cleared");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Type-checked coord reader: Bundle getters silently return the default
     *  (NaN) for a wrong type instead of throwing, so read the raw value and
     *  convert. Handles --ef Float, --ed Double, --el Long, --ei Int, --es String. */
    private static double readCoord(Intent i, String... keys) {
        if (i == null) return Double.NaN;
        for (String k : keys) {
            if (!i.hasExtra(k)) continue;
            Object v = null;
            try { v = i.getExtras().get(k); } catch (Throwable t) {}
            if (v instanceof Number) return ((Number) v).doubleValue();
            try { return i.getDoubleExtra(k, Double.NaN); } catch (Throwable t) {}
            try { return (double) i.getFloatExtra(k, Float.NaN); } catch (Throwable t) {}
        }
        return Double.NaN;
    }

    /** Convenience for external starters (adb broadcast receiver / UI):
     *  keep the mock fresh for the natural lifetime (default 90s), then release
     *  so the device reverts to real GPS. */
    public static void start(Context ctx, double lat, double lon) {
        Intent i = new Intent(ctx, MockService.class);
        i.putExtra("lat", lat);
        i.putExtra("lon", lon);
        ctx.startForegroundService(i);
    }

    /** Persistent map-coverage mode: keep re-pinning every ~1s forever. */
    public static void startPersistent(Context ctx, double lat, double lon) {
        Intent i = new Intent(ctx, MockService.class);
        i.putExtra("lat", lat);
        i.putExtra("lon", lon);
        i.putExtra("persistent", true);
        ctx.startForegroundService(i);
    }

    /** Stop + clear the mock from any context. */
    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, MockService.class);
        i.putExtra("cmd", "stop");
        ctx.startService(i);   // delivers cmd=stop to onStartCommand -> stopSelf()
        ctx.stopService(new Intent(ctx, MockService.class));
    }
}
