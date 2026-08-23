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

    // Live status surfaced to MainActivity polling.
    public static boolean running = false;
    public static long lastTick = 0;
    public static int tickCount = 0;
    public static double curLat = 0;
    public static double curLon = 0;

    private LocationManager lm;
    private PowerManager.WakeLock wl;
    private Handler loop;
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

        double lat = intent != null ? intent.getDoubleExtra("lat", Double.NaN) : Double.NaN;
        double lon = intent != null ? intent.getDoubleExtra("lon", Double.NaN) : Double.NaN;
        if (intent != null && intent.hasExtra("latitude") && Double.isNaN(lat)) {
            lat = intent.getDoubleExtra("latitude", Double.NaN);
        }
        if (intent != null && intent.hasExtra("longitude") && Double.isNaN(lon)) {
            lon = intent.getDoubleExtra("longitude", Double.NaN);
        }

        if (running) {
            // Already alive: just update the target coordinates.
            if (!Double.isNaN(lat)) curLat = lat;
            if (!Double.isNaN(lon)) curLon = lon;
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
        loop = new Handler(Looper.getMainLooper());
        loop.postDelayed(tick, INTERVAL_MS);
        running = true;
        Log.i(TAG, String.format("service started, pinning %.6f, %.6f", curLat, curLon));
        return START_STICKY;
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
        if (loop != null) loop.removeCallbacks(tick);
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

    /** Convenience for external starters (adb broadcast receiver). */
    public static void start(Context ctx, double lat, double lon) {
        Intent i = new Intent(ctx, MockService.class);
        i.putExtra("lat", lat);
        i.putExtra("lon", lon);
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
