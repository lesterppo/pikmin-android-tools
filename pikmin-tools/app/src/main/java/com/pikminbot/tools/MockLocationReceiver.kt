package com.pikminbot.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
class MockLocationReceiver : BroadcastReceiver() {

    private val TAG = "PikminBotTools"

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == ACTION_CLEAR) {
            context.stopService(Intent(context, MockService::class.java))
            Log.i(TAG, "cleared mock location (via activity stop)")
            return
        }
        if (action != ACTION_SET) return

        val lat = readDouble(intent, "lat", "latitude")
        val lon = readDouble(intent, "lon", "lng", "longitude")
        if (lat.isNaN() || lon.isNaN()) {
            Log.w(TAG, "missing lat/lon extras")
            return
        }
        // Launch the Activity (allowed context) which starts the service.
        val launch = Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch.putExtra("lat", lat)
        launch.putExtra("lon", lon)
        launch.putExtra(MainActivity.EXTRA_AUTOSTART, true)
        context.startActivity(launch)
        Log.i(TAG, String.format("launching MainActivity to pin %.6f, %.6f", lat, lon))
    }

    private fun readDouble(i: Intent, vararg keys: String): Double {
        for (k in keys) {
            if (!i.hasExtra(k)) continue
            // Type-check first: Bundle getters NEVER throw on a wrong type —
            // they silently return the default (NaN), so a getDouble->getFloat
            // fallback chain doesn't work. Handle whatever the am tool stored
            // (--ef Float, --ed Double, --el Long, --ei Int, --es String).
            val v: Any? = try { i.extras?.get(k) } catch (t: Throwable) { null }
            if (v is Number) return v.toDouble()
            try { return i.getDoubleExtra(k, Double.NaN) } catch (t: Throwable) {}
            try { return i.getFloatExtra(k, Float.NaN).toDouble() } catch (t: Throwable) {}
        }
        return Double.NaN
    }

    companion object {
        const val ACTION_SET = "com.pikminbot.mock.SET_LOCATION"
        const val ACTION_CLEAR = "com.pikminbot.mock.CLEAR_LOCATION"
    }
}
