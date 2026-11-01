package com.pikminbot.tools

import android.content.Context
import android.content.SharedPreferences
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.time.ZoneId

/**
 * Mock time / date / timezone injection (v2.5).
 *
 * WHAT CAN AND CANNOT BE DONE WITHOUT ROOT
 * ----------------------------------------
 * Wall-clock injection is a privileged operation: the only APIs that move the
 * system clock are AlarmManager.setTime() / setTimeZone(), which are guarded by
 * android.permission.SET_TIME and SET_TIME_ZONE — both `signature`, so they can
 * never be granted to a normal app with `pm grant`.
 *
 * The shell uid (2000) CAN do it. Verified on Android 16 / SM-A3760:
 *
 *   adb shell cmd time_detector set_auto_detection_enabled false
 *   adb shell cmd alarm set-timezone Asia/Tokyo     -> persist.sys.timezone updated, date flips to JST
 *   adb shell cmd alarm set-time 1793631837000      -> clock jumps, holds (no auto resync)
 *
 * so the app reaches the same effect through an execution ladder:
 *
 *   tier 0  root      `su -c "<cmd>"`                       (rooted devices)
 *   tier 1  Shizuku   IShizukuService.newProcess("sh -c")   (stock device + Shizuku running + permitted)
 *   tier 2  none      report exactly what is missing and how to fix it
 *
 * WRITE_SECURE_SETTINGS is NOT enough on Android 12+: writing Settings.Global
 * TIME_ZONE no longer applies to the running system (the TimeZoneWatcher that
 * used to apply it is gone — the timezone is owned by time_zone_detector), and
 * writing AUTO_TIME alone does not move the clock. Verified on device.
 *
 * AUTO-RESYNC: the clock is re-applied by the network unless automatic time
 * detection is switched off first, which is why every apply() disables both
 * `time_detector` auto detection and Settings.Global.AUTO_TIME / AUTO_TIME_ZONE
 * BEFORE setting the clock, and restore() turns them back on.
 */
object MockTime {

    private const val TAG = "PikminBotTools"
    private const val PREFS = "mocktime"
    private const val K_ACTIVE = "active"
    private const val K_EPOCH = "epoch"
    private const val K_TZ = "tz"
    private const val K_SAVED_AUTO_TIME = "saved_auto_time"
    private const val K_SAVED_AUTO_TZ = "saved_auto_tz"
    private const val K_SAVED_TZ = "saved_tz"

    private val ISO = SimpleDateFormat("EEE yyyy-MM-dd HH:mm:ss zzz", Locale.US)

    /** Result of one privileged execution attempt. */
    data class Res(val ok: Boolean, val tier: String, val msg: String)

    /** Live view of what the device clock reads right now. */
    data class Snapshot(
        val text: String,
        val tz: String,
        val autoTime: String,
        val autoTz: String,
        val epochMs: Long
    )

    // ------------------------------------------------------------- capabilities

    fun shizukuBinderAlive(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    fun shizukuPermitted(): Boolean = try {
        shizukuBinderAlive() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    /** Ask Shizuku for permission (shows Shizuku's own consent dialog). */
    fun requestShizukuPermission(requestCode: Int = 4242) {
        try {
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) return
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Log.w(TAG, "mocktime: shizuku permission request failed", t)
        }
    }

    /** Null when a privileged tier is usable, else the reason it is not. */
    fun missingTier(): String? = when {
        rootUsable() -> null
        shizukuPermitted() -> null
        shizukuBinderAlive() -> "Shizuku is running but has not authorised PikminBot Tools — tap \"Grant Shizuku\""
        else -> "No root and Shizuku is not running.\n" +
                "Start Shizuku (wireless-debugging start in the Shizuku app) once after each reboot,\n" +
                "then tap \"Grant Shizuku\". Without it the clock cannot be moved: SET_TIME is a signature permission."
    }

    fun tierName(): String = when {
        rootUsable() -> "root"
        shizukuPermitted() -> "shizuku"
        shizukuBinderAlive() -> "shizuku(unauthorised)"
        else -> "none"
    }

    @Volatile
    private var rootCache: Boolean? = null

    private fun rootUsable(): Boolean {
        rootCache?.let { return it }
        val ok = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val done = p.waitFor(4, TimeUnit.SECONDS)
            if (!done) { p.destroy(); false } else p.exitValue() == 0
        } catch (t: Throwable) { false }
        rootCache = ok
        return ok
    }

    fun resetRootCache() { rootCache = null }

    // ----------------------------------------------------------------- execution

    private fun readAll(s: InputStream?): String = try {
        s?.bufferedReader()?.use { it.readText().trim() } ?: ""
    } catch (t: Throwable) { "" }

    /** Shizuku hands back ParcelFileDescriptor streams, not java streams. */
    private fun readPfd(pfd: ParcelFileDescriptor?): String = try {
        if (pfd == null) "" else java.io.FileInputStream(pfd.fileDescriptor).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
        }
    } catch (t: Throwable) { "" }

    private fun exec(cmd: String): Res {
        if (rootUsable()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val done = p.waitFor(20, TimeUnit.SECONDS)
                if (!done) { p.destroy(); return Res(false, "root", "timeout") }
                val out = readAll(p.inputStream) + readAll(p.errorStream)
                return Res(p.exitValue() == 0, "root", out.ifBlank { "rc=${p.exitValue()}" })
            } catch (t: Throwable) {
                Log.w(TAG, "mocktime: root exec failed", t)
            }
        }
        if (shizukuPermitted()) {
            return try {
                val binder: IBinder = Shizuku.getBinder()
                    ?: return Res(false, "shizuku", "shizuku binder unavailable")
                val svc = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
                val p = svc.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val done = p.waitForTimeout(20, "SECONDS")
                if (!done) {
                    try { p.destroy() } catch (t: Throwable) {}
                    return Res(false, "shizuku", "timeout")
                }
                val rc = p.exitValue()
                val out = (readPfd(p.inputStream) + "\n" + readPfd(p.errorStream)).trim()
                Res(rc == 0, "shizuku", out.ifBlank { "rc=$rc" })
            } catch (t: Throwable) {
                Log.w(TAG, "mocktime: shizuku exec failed", t)
                Res(false, "shizuku", "shizuku exec failed: ${t.javaClass.simpleName}")
            }
        }
        return Res(false, "none", missingTier() ?: "no privileged tier")
    }

    // -------------------------------------------------------------------- public

    /** Read the zone the SYSTEM is using (in-process java default can be stale
     *  until something resets it, which is why the app used to show GMT+08:00
     *  while the device ran on Asia/Tokyo). */
    fun systemZoneId(): String? {
        val r = exec("getprop persist.sys.timezone")
        if (!r.ok) return null
        val id = r.msg.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return id.takeIf { isValidZone(it) }
    }

    /** Re-sync this process' java.util.TimeZone with the system property. */
    fun syncDefaultZone() {
        try {
            val id = systemZoneId() ?: return
            if (TimeZone.getDefault().id != id) TimeZone.setDefault(TimeZone.getTimeZone(id))
        } catch (t: Throwable) { }
    }

    fun snapshot(ctx: Context): Snapshot {
        val now = System.currentTimeMillis()
        // While a mock is active prefer the injected zone: the in-process java
        // default can lag the platform and would label a mocked clock with the
        // pre-mock zone (e.g. GMT+09:00 instead of Asia/Tokyo).
        val mockTz = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_TZ, null)
        val tz = if (mockTz != null && isValidZone(mockTz)) TimeZone.getTimeZone(mockTz) else TimeZone.getDefault()
        val auto = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, -1).toString()
        } catch (t: Throwable) { "?" }
        val autoTz = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, -1).toString()
        } catch (t: Throwable) { "?" }
        // Render the Olson id explicitly: Android 16/ICU returns "GMT+09:00"
        // for zzz on many zones, which hides WHICH zone was injected.
        val fmt = SimpleDateFormat("EEE yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }
        return Snapshot(fmt.format(Date(now)) + " " + tz.id, tz.id, auto, autoTz, now)
    }

    fun isValidZone(id: String): Boolean = try {
        ZoneId.of(id.trim()); true
    } catch (t: Throwable) { false }

    /** Common zones offered in the UI (id + label). */
    fun zoneChoices(): List<Pair<String, String>> = listOf(
        "Asia/Hong_Kong" to "Hong Kong",
        "Asia/Tokyo" to "Tokyo (+1)",
        "Asia/Seoul" to "Seoul (+1)",
        "Asia/Shanghai" to "Shanghai (0)",
        "Asia/Singapore" to "Singapore (0)",
        "Asia/Bangkok" to "Bangkok (-1)",
        "Asia/Kolkata" to "Kolkata (-2:30)",
        "Asia/Dubai" to "Dubai (-4)",
        "Europe/London" to "London (-8)",
        "Europe/Berlin" to "Berlin (-7)",
        "UTC" to "UTC (-8)",
        "America/New_York" to "New York (-13)",
        "America/Los_Angeles" to "Los Angeles (-16)",
        "Pacific/Auckland" to "Auckland (+4)"
    )

    /**
     * Apply a mock clock. [epochMs] null = leave the clock alone (timezone-only
     * injection). [tzId] null = keep the current zone.
     */
    fun applyMock(ctx: Context, epochMs: Long?, tzId: String?): String {
        val log = StringBuilder()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rememberOriginal(ctx, prefs)

        // 1. stop the platform from fighting us
        val autoOff = exec(
            "cmd time_detector set_auto_detection_enabled false; " +
            "settings put global auto_time 0; " +
            "settings put global auto_time_zone 0; " +
            "cmd time_zone_detector set_auto_detection_enabled false; " +
            "cmd time_zone_detector set_geo_detection_enabled false"
        )
        log.append("auto-off [${autoOff.tier}] ok=${autoOff.ok}\n")
        if (!autoOff.ok && autoOff.tier == "none") return log.append(autoOff.msg).toString()

        // 2. timezone
        var appliedTz: String? = null
        if (tzId != null) {
            if (!isValidZone(tzId)) {
                log.append("timezone rejected: unknown zone '$tzId'\n")
            } else {
                val r = exec("cmd alarm set-timezone $tzId")
                log.append("timezone=$tzId [${r.tier}] ok=${r.ok} ${r.msg}\n")
                if (r.ok) {
                    appliedTz = tzId
                    try { TimeZone.setDefault(TimeZone.getTimeZone(tzId)) } catch (t: Throwable) {}
                }
            }
        }

        // 3. clock
        var appliedEpoch: Long? = null
        if (epochMs != null) {
            val r = exec("cmd alarm set-time $epochMs; date +%s")
            log.append("set-time $epochMs [${r.tier}] ok=${r.ok} ${r.msg}\n")
            if (r.ok) appliedEpoch = epochMs
        }

        prefs.edit()
            .putBoolean(K_ACTIVE, true)
            .putLong(K_EPOCH, appliedEpoch ?: -1L)
            .putString(K_TZ, appliedTz ?: "")
            .apply()
        log.append("now: ").append(snapshot(ctx).text)
        return log.toString()
    }

    /** Shift the (already device-visible) clock by [hours] hours. */
    fun shift(ctx: Context, hours: Double): String {
        val target = System.currentTimeMillis() + (hours * 3_600_000.0).toLong()
        val r = exec("cmd alarm set-time $target")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rememberOriginal(ctx, prefs)
        prefs.edit().putBoolean(K_ACTIVE, true).putLong(K_EPOCH, target).apply()
        return "shift ${if (hours >= 0) "+" else ""}${hours}h [${
            r.tier
        }] ok=${r.ok} ${r.msg}\nnow: ${snapshot(ctx).text}"
    }

    /** Turn automatic time/zone detection back on and clear the mock marker. */
    fun restore(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val origTz = prefs.getString(K_SAVED_TZ, null)
        val origAuto = prefs.getInt(K_SAVED_AUTO_TIME, 1)
        val origAutoTz = prefs.getInt(K_SAVED_AUTO_TZ, 1)
        val sb = StringBuilder()
        if (origTz != null && isValidZone(origTz)) {
            val r = exec("cmd alarm set-timezone $origTz")
            sb.append("timezone back to $origTz [${r.tier}] ok=${r.ok}\n")
        }
        val r = exec(
            "settings put global auto_time $origAuto; " +
            "settings put global auto_time_zone $origAutoTz; " +
            "cmd time_detector set_auto_detection_enabled true; " +
            "cmd time_zone_detector set_auto_detection_enabled true"
        )
        sb.append("auto-detect restored [${r.tier}] ok=${r.ok}\n")
        prefs.edit().putBoolean(K_ACTIVE, false).putLong(K_EPOCH, -1L).putString(K_TZ, "").apply()
        try { TimeZone.setDefault(TimeZone.getTimeZone(origTz ?: TimeZone.getDefault().id)) } catch (t: Throwable) {}
        sb.append("device will resync from the network; now: ").append(snapshot(ctx).text)
        return sb.toString()
    }

    fun isActive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ACTIVE, false)

    /** Remember the pre-mock settings once, so restore() can put them back. */
    private fun rememberOriginal(ctx: Context, prefs: SharedPreferences) {
        if (prefs.contains(K_SAVED_TZ)) return
        val e = prefs.edit()
        val sysTz = systemZoneId()
        e.putString(K_SAVED_TZ, sysTz ?: TimeZone.getDefault().id)
        e.putInt(
            K_SAVED_AUTO_TIME,
            try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 1) } catch (t: Throwable) { 1 }
        )
        e.putInt(
            K_SAVED_AUTO_TZ,
            try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) } catch (t: Throwable) { 1 }
        )
        e.apply()
    }
}
