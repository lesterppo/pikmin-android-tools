package com.pikminbot.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import moe.shizuku.server.IShizukuService

/**
 * Self-heal for the MOCK_LOCATION appop.
 *
 * The device allows only ONE mock-location app. When the user (or another
 * companion app) re-selects a different package, the appop silently moves
 * away and addTestProvider starts throwing SecurityException. This helper
 * repairs it in three tiers:
 *
 *  1. Shizuku (if running + permitted): shell `appops set` + `settings put`
 *  2. WRITE_SECURE_SETTINGS (if granted): self-select via Settings.Secure
 *  3. Otherwise: notification pointing at Developer options
 *
 * Attempts are capped at 3 per service lifetime; every attempt is logged
 * under the shared "PikminBotTools" tag.
 */
object SelfHeal {

    private const val TAG = "PikminBotTools"
    const val NOTIF_ID = 2
    private const val MAX_ATTEMPTS = 3

    @Volatile
    var lastSecurityExceptionMs: Long = 0
        private set

    @Volatile
    var attempts: Int = 0
        private set

    @Volatile
    var lastRepair: String = ""
        private set

    fun reset() {
        attempts = 0
        lastSecurityExceptionMs = 0
        lastRepair = ""
    }

    fun noteSecurityException() {
        lastSecurityExceptionMs = System.currentTimeMillis()
    }

    /**
     * Run one repair tier and return true when the appop looks restored
     * (caller should retry injection).
     */
    fun attemptRepair(ctx: Context, source: String): Boolean {
        lastSecurityExceptionMs = System.currentTimeMillis()
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "self-heal cap reached ($attempts/$MAX_ATTEMPTS), giving up (from $source)")
            return false
        }
        attempts++
        Log.w(TAG, "self-heal attempt $attempts/$MAX_ATTEMPTS (from $source)")

        // Tier 1: Shizuku shell
        if (tryShizuku()) {
            Log.i(TAG, "self-heal: appop restored via Shizuku shell")
            lastRepair = "shizuku"
            return true
        }
        // Tier 2: WRITE_SECURE_SETTINGS self-select
        if (tryWriteSecureSettings(ctx)) {
            Log.i(TAG, "self-heal: mock-location app self-selected via WRITE_SECURE_SETTINGS")
            lastRepair = "write_secure_settings"
            return true
        }
        // Tier 3: point the user at Developer options
        postFixNotification(ctx)
        lastRepair = "notification"
        return false
    }

    // ------------------------------------------------------------ tier 1
    private fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun tryShizuku(): Boolean {
        if (!shizukuAlive()) {
            Log.i(TAG, "self-heal: Shizuku not alive/permitted, skipping tier 1")
            return false
        }
        return try {
            val binder: IBinder = Shizuku.getBinder()
                ?: return false
            val svc = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            val cmd = ("appops set com.pikminbot.tools android:mock_location allow; " +
                       "settings put secure mock_location com.pikminbot.tools")
            val p = svc.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val done = p.waitForTimeout(10, "SECONDS")
            if (!done) {
                try { p.destroy() } catch (t: Throwable) {}
                Log.e(TAG, "self-heal: Shizuku shell timed out")
                return false
            }
            val rc = p.exitValue()
            Log.i(TAG, "self-heal: Shizuku shell rc=$rc")
            rc == 0
        } catch (t: Throwable) {
            Log.e(TAG, "self-heal: Shizuku shell failed", t)
            false
        }
    }

    // ------------------------------------------------------------ tier 2
    private fun tryWriteSecureSettings(ctx: Context): Boolean {
        val granted = try {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        if (!granted) {
            Log.i(TAG, "self-heal: WRITE_SECURE_SETTINGS not granted, skipping tier 2")
            return false
        }
        return try {
            Settings.Secure.putString(ctx.contentResolver, "mock_location", ctx.packageName)
            Log.i(TAG, "self-heal: wrote secure mock_location=${ctx.packageName}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "self-heal: WRITE_SECURE_SETTINGS write failed", t)
            false
        }
    }

    // ------------------------------------------------------------ tier 3
    private fun postFixNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("selfheal", "Self-heal", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                Notification.Builder(ctx, "selfheal") else Notification.Builder(ctx)
            b.setContentTitle("Mock location permission lost")
                .setContentText("Tap to fix")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(NOTIF_ID, b.build())
        } catch (t: Throwable) {
            Log.e(TAG, "self-heal: fix-notification failed", t)
        }
    }

    /** Best-effort appop probe: true when Settings reports us as mock app. */
    fun isMockAppSelected(ctx: Context): Boolean = try {
        val cur = Settings.Secure.getString(ctx.contentResolver, "mock_location")
        cur == ctx.packageName
    } catch (t: Throwable) {
        false
    }
}
