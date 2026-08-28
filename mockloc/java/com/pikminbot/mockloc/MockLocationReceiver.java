package com.pikminbot.mockloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Entry point for adb-driven injection (used by the pbgw drift bridge):
 *   am broadcast -a com.pikminbot.mock.SET_LOCATION --ef lat 22.3193 --ef lon 114.1694
 *   am broadcast -a com.pikminbot.mock.CLEAR_LOCATION
 *
 * On Android 14+ a BroadcastReceiver is NOT allowed to call
 * startForegroundService (the call is silently DENIED). So instead we launch
 * the app's MainActivity with the coordinates + autostart flag; the Activity
 * (a permitted context) then starts MockService. This keeps injection fully
 * on-device with no computer loop.
 */
public class MockLocationReceiver extends BroadcastReceiver {

    private static final String TAG = "PikminMockLoc";
    public static final String ACTION_SET = "com.pikminbot.mock.SET_LOCATION";
    public static final String ACTION_CLEAR = "com.pikminbot.mock.CLEAR_LOCATION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (action.equals(ACTION_CLEAR)) {
            context.stopService(new Intent(context, MockService.class));
            Log.i(TAG, "cleared mock location (via activity stop)");
            return;
        }
        if (!action.equals(ACTION_SET)) return;

        double lat = readDouble(intent, "lat", "latitude");
        double lon = readDouble(intent, "lon", "lng", "longitude");
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            Log.w(TAG, "missing lat/lon extras");
            return;
        }
        // Launch the Activity (allowed context) which starts the service.
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch.putExtra("lat", lat);
        launch.putExtra("lon", lon);
        launch.putExtra(MainActivity.EXTRA_AUTOSTART, true);
        context.startActivity(launch);
        Log.i(TAG, String.format("launching MainActivity to pin %.6f, %.6f", lat, lon));
    }

    private static double readDouble(Intent i, String... keys) {
        for (String k : keys) {
            if (!i.hasExtra(k)) continue;
            // Type-check first: Bundle getters NEVER throw on a wrong type —
            // they silently return the default (NaN), so a getDouble->getFloat
            // fallback chain doesn't work. Handle whatever the am tool stored
            // (--ef Float, --ed Double, --el Long, --ei Int, --es String).
            Object v = null;
            try { v = i.getExtras().get(k); } catch (Throwable t) {}
            if (v instanceof Number) return ((Number) v).doubleValue();
            try { return i.getDoubleExtra(k, Double.NaN); } catch (Throwable t) {}
            try { return (double) i.getFloatExtra(k, Float.NaN); } catch (Throwable t) {}
        }
        return Double.NaN;
    }
}
