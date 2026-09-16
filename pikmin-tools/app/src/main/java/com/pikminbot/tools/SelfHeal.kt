package com.pikminbot.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import moe.shizuku.server.IShizukuService

/**
 * Self-heal for the MOCK_LOCATION appop (v2).
 *
 * WHAT BREAKS IT: toggling Developer options off and on clears Android's
 * "Select mock location app" slot, which returns the MOCK_LOCATION appop to
 * its default (deny). The `mock_location` Settings value can still name this
 * package, so the settings UI looks right while every addTestProvider /
 * setTestProviderLocation call throws SecurityException. Live-verified
 * 2026-09-17: after the toggle, `appops get` read "deny" while
 * `settings get secure mock_location` read the app's own package name.
 *
 * WHY THE OLD TIER 2 DID NOT WORK: writing Settings.Secure("mock_location")
 * does NOT move the appop. Verified on-device: appop forced to deny, setting
 * then written to this package, appop still deny. Only the Settings app's own
 * AppOpsService call (or a shell) sets MODE_ALLOWED, and an app can never
 * flip its own appop (needs MANAGE_APP_OPS_MODES, signature|privileged).
 *
 * TIERS (first one that reports success wins):
 *  0. root (`su -c appops set ...`) — the only fully automatic path on a rooted device
 *  1. Shizuku shell (running + permitted) — fully automatic on a stock device
 *  2. WRITE_SECURE_SETTINGS — re-writes the selection only (necessary but NOT
 *     sufficient on its own); kept because some ROMs do sync the appop here
 *  3. notification with a one-tap Repair action + guided screen
 *
 * Rate limit is time-based, not lifetime-based: the old cap of 3 attempts per
 * service lifetime meant that after a developer-mode toggle the app silently
 * gave up forever. Backoff is reset by noteEnvChange() whenever SlotWatch
 * reports the developer-mode / selection environment moving.
 */
object SelfHeal {

    private const val TAG = "PikminBotTools"
    const val NOTIF_ID = 2
    private const val MIN_INTERVAL_MS = 5000L
    private const val NOTIF_INTERVAL_MS = 30_000L
    private var lastNotifMs = 0L

    /** Progressive backoff: fast while the environment is moving, slow once it
     *  is clear only a human in Developer options can fix it (no root/Shizuku).
     *  3 rapid tries, then 15 s, then a minute — keeps the tick loop cheap. */
    private fun intervalMs(): Long = when {
        attempts <= 3 -> MIN_INTERVAL_MS
        attempts <= 6 -> 15_000L
        else -> 60_000L
    }

    @Volatile
    var lastSecurityExceptionMs: Long = 0
        private set

    /** Attempts since the last environment change (surfaced in the UI). */
    @Volatile
    var attempts: Int = 0
        private set

    @Volatile
    var totalAttempts: Int = 0
        private set

    @Volatile
    var lastRepair: String = ""
        private set

    @Volatile
    var lastAttemptMs: Long = 0
        private set

    @Volatile
    var lastEnvChange: String = ""
        private set

    fun noteSecurityException() {
        lastSecurityExceptionMs = System.currentTimeMillis()
    }

    /** SlotWatch reports the developer-mode / selection environment moving. */
    fun noteEnvChange(reason: String) {
        lastEnvChange = reason
        attempts = 0
        lastAttemptMs = 0
        Log.i(TAG, "self-heal: backoff reset ($reason)")
    }

    fun reset() {
        attempts = 0
        totalAttempts = 0
        lastSecurityExceptionMs = 0
        lastRepair = ""
        lastAttemptMs = 0
    }

    // ---------------------------------------------------------------- probes

    /** True only when the appop itself is allowed for this app. */
    fun appopAllowed(ctx: Context?): Boolean {
        val c = ctx ?: return false
        return try {
            val aom = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                aom.unsafeCheckOpNoThrow("android:mock_location", Process.myUid(), c.packageName)
            } else {
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow("android:mock_location", Process.myUid(), c.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Log.e(TAG, "self-heal: appop probe failed", t)
            false
        }
    }

    /** True when Settings names this package as the mock app. Necessary, not sufficient. */
    fun isMockAppSelected(ctx: Context): Boolean = try {
        Settings.Secure.getString(ctx.contentResolver, "mock_location") == ctx.packageName
    } catch (t: Throwable) { false }

    /** The real health check: appop allowed AND selected. */
    fun slotOk(ctx: Context?): Boolean {
        val c = ctx ?: return false
        return appopAllowed(c) && isMockAppSelected(c)
    }

    fun describe(ctx: Context?): String {
        val c = ctx ?: return "no context"
        val sel = try { Settings.Secure.getString(c.contentResolver, "mock_location") ?: "(none)" }
            catch (t: Throwable) { "?" }
        return "appop=${if (appopAllowed(c)) "allow" else "deny"} selection=$sel"
    }

    // ---------------------------------------------------------------- repair

    /**
     * Run the repair chain. Returns true when the slot is healthy afterwards
     * (caller should retry injection).
     */
    fun attemptRepair(ctx: Context, source: String): Boolean {
        lastSecurityExceptionMs = System.currentTimeMillis()
        if (slotOk(ctx)) {
            lastRepair = "already-ok"
            return true
        }
        val now = System.currentTimeMillis()
        val wait = intervalMs()
        if (now - lastAttemptMs < wait) {
            Log.i(TAG, "self-heal: throttled (${(wait - (now - lastAttemptMs)) / 1000}s left)")
            return false
        }
        lastAttemptMs = now
        attempts++
        totalAttempts++
        Log.w(TAG, "self-heal attempt #$attempts (total $totalAttempts) from $source — ${describe(ctx)}")

        if (tryRoot(ctx)) { lastRepair = "root"; return slotOk(ctx) }
        if (tryShizuku(ctx)) { lastRepair = "shizuku"; return slotOk(ctx) }
        if (tryWriteSecureSettings(ctx)) {
            lastRepair = "write_secure_settings"
            if (slotOk(ctx)) return true
            Log.w(TAG, "self-heal: setting written but appop still denied — needs Developer options")
        }
        postFixNotification(ctx, source)
        if (lastRepair != "write_secure_settings") lastRepair = "notification"
        return false
    }

    // ------------------------------------------------------------ tier 0: root
    private fun tryRoot(ctx: Context): Boolean {
        val cmd = "appops set ${ctx.packageName} android:mock_location allow; " +
                  "settings put secure mock_location ${ctx.packageName}"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroy(); Log.i(TAG, "self-heal: root timed out"); return false }
            val rc = p.exitValue()
            Log.i(TAG, "self-heal: root rc=$rc")
            rc == 0
        } catch (t: Throwable) {
            Log.i(TAG, "self-heal: no root (${t.javaClass.simpleName})")
            false
        }
    }

    // -------------------------------------------------------- tier 1: Shizuku
    private fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    /** Shizuku is installed but not running / not authorised for this app. */
    fun shizukuAvailable(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    private fun tryShizuku(ctx: Context): Boolean {
        if (!shizukuAlive()) {
            Log.i(TAG, "self-heal: Shizuku not alive/permitted, skipping tier 1")
            return false
        }
        return try {
            val binder: IBinder = Shizuku.getBinder() ?: return false
            val svc = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            val cmd = ("appops set ${ctx.packageName} android:mock_location allow; " +
                       "settings put secure mock_location ${ctx.packageName}")
            val p = svc.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val done = p.waitForTimeout(10, "SECONDS")
            if (!done) {
                try { p.destroy() } catch (t: Throwable) {}
                Log.e(TAG, "self-heal: Shizuku shell timed out")
                return false
            }
            Log.i(TAG, "self-heal: Shizuku shell rc=${p.exitValue()}")
            p.exitValue() == 0
        } catch (t: Throwable) {
            Log.e(TAG, "self-heal: Shizuku shell failed", t)
            false
        }
    }

    // --------------------------------------------- tier 2: WRITE_SECURE_SETTINGS
    private fun tryWriteSecureSettings(ctx: Context): Boolean {
        val granted = try {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) { false }
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

    // ---------------------------------------------------- tier 3: notification
    /** Notification whose tap opens the in-app repair screen (not raw Settings). */
    fun postFixNotification(ctx: Context, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifMs < NOTIF_INTERVAL_MS) {
            Log.i(TAG, "self-heal: notification throttled (from $source)")
            return
        }
        lastNotifMs = now
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("selfheal", "Mock location repair", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = Intent(ctx, RepairActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(ctx, "selfheal") else Notification.Builder(ctx)
            b.setContentTitle("Mock location lost — tap to repair")
                .setContentText("Developer options changed; re-select PikminBot Tools")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_menu_manage, "Repair",
                        PendingIntent.getActivity(ctx, 1, open,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    ).build()
                )
            nm.notify(NOTIF_ID, b.build())
            Log.i(TAG, "self-heal: repair notification posted (from $source)")
        } catch (t: Throwable) {
            Log.e(TAG, "self-heal: fix-notification failed", t)
        }
    }

    fun clearFixNotification(ctx: Context) {
        lastNotifMs = 0L
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (t: Throwable) {}
    }
}
