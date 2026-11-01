package com.pikminbot.tools

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local-ADB tier (v3.0) — the LADB approach, self-contained.
 *
 * WHY THIS EXISTS: the clock can only be moved by the shell uid (SET_TIME is a
 * signature permission). Shizuku provides such a channel but needs a third-party
 * daemon that must be restarted after every reboot. This tier gives the app its
 * OWN shell channel: it ships an `adb` client binary as `libadb.so` in the APK's
 * native library directory (the only place an app may exec from since the
 * Android 10 W^X change) and uses it to pair with the phone's own wireless
 * debugging service over loopback, then run commands as uid 2000.
 *
 * FLOW (each step verified against `adb` 1.0.41 / android-tools 37.0.0p1):
 *   1. `libadb.so pair 127.0.0.1:<pairing-port>` with the 6-digit code on stdin
 *      → our own adb key is added to the device's trusted set
 *   2. `libadb.so connect 127.0.0.1:<connect-port>`  (mDNS `_adb-tls-connect`)
 *   3. `libadb.so -s 127.0.0.1:<port> shell <cmd>`
 *
 * The key lives in $HOME/.android inside app-private storage, so the pairing
 * survives reboots and app restarts; only "revoke USB debugging authorisations"
 * or clearing app data forces a re-pair.
 *
 * Binary provenance: Termux `android-tools` 37.0.0p1 (AOSP adb + its shared
 * libraries, Apache-2.0), arm64-v8a only.
 */
object LocalAdb {

    private const val TAG = "PikminBotTools"
    private const val PREFS = "localadb"
    private const val K_PORT = "connect_port"
    private const val K_PAIRED = "paired"

    private const val SVC_CONNECT = "_adb-tls-connect._tcp"
    private const val SVC_PAIRING = "_adb-tls-pairing._tcp"

    data class Res(val ok: Boolean, val msg: String)
    data class Ports(val connect: Int?, val pairing: Int?)

    @Volatile
    var discoveredConnectPort: Int? = null
        private set

    @Volatile
    var discoveredPairingPort: Int? = null
        private set

    // ------------------------------------------------------------------ paths

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libadb.so")

    fun available(ctx: Context): Boolean = binary(ctx).exists()

    private fun home(ctx: Context): File =
        File(ctx.filesDir, "adbhome").apply { mkdirs() }

    private fun port(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(K_PORT, 0)

    fun connectPort(ctx: Context): Int = port(ctx)

    private val initOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Called from MockTime.init: silently (re)connect in the background so the
     *  first clock operation does not pay for the adb handshake. */
    fun ensureConnectedLater(ctx: Context) {
        if (!available(ctx) || !isPaired(ctx)) return
        if (!initOnce.compareAndSet(false, true)) return
        Thread {
            try { ensureConnected(ctx) } catch (t: Throwable) { }
        }.start()
    }

    fun isPaired(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_PAIRED, false)

    private fun savePort(ctx: Context, p: Int, paired: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_PORT, p).putBoolean(K_PAIRED, paired).apply()
    }

    // --------------------------------------------------------------- execution

    /** Run the bundled adb client. [stdinLine] is written (with a newline) then
     *  stdin is closed — `adb pair` reads the code that way. */
    private fun run(
        ctx: Context,
        args: List<String>,
        stdinLine: String? = null,
        timeoutSec: Long = 40
    ): Res {
        val bin = binary(ctx)
        if (!bin.exists()) return Res(false, "libadb.so missing from $bin")
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", home(ctx).absolutePath)
                put("TMPDIR", ctx.cacheDir.absolutePath)
                put("LD_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir)
                put("ADB_VENDOR_KEYS", "")
            }
            val p = pb.start()
            if (stdinLine != null) {
                try {
                    p.outputStream.write((stdinLine + "\n").toByteArray())
                    p.outputStream.flush()
                } catch (t: Throwable) {
                }
            }
            try { p.outputStream.close() } catch (t: Throwable) {}
            val out = try { p.inputStream.bufferedReader().readText() } catch (t: Throwable) { "" }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                try { p.destroyForcibly() } catch (t: Throwable) {}
                return Res(false, "timeout after ${timeoutSec}s: " + squash(out))
            }
            val cleaned = squash(out)
            val ok = p.exitValue() == 0 &&
                    !cleaned.contains("failed", ignoreCase = true) &&
                    !cleaned.contains("cannot", ignoreCase = true) &&
                    !cleaned.contains("error:", ignoreCase = true)
            Res(ok, cleaned.ifBlank { "rc=${p.exitValue()}" })
        } catch (t: Throwable) {
            Log.w(TAG, "localadb: exec failed", t)
            Res(false, "exec failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** adb prints WARNING/absl noise before every command; keep the useful line. */
    private fun squash(s: String): String = s.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("WARNING: All log messages") }
        .filterNot { it.matches(Regex("^[0-9:\\-\\. ]+F\\d+ .*")) }
        .filterNot { it.startsWith("* daemon") }
        .joinToString(" | ")
        .take(600)

    fun startServer(ctx: Context): Res = run(ctx, listOf("start-server"), timeoutSec = 25)

    fun devices(ctx: Context): String = run(ctx, listOf("devices")).msg

    // ----------------------------------------------------------------- pairing

    fun pair(ctx: Context, pairingPort: Int, code: String): Res {
        val r = run(ctx, listOf("pair", "127.0.0.1:$pairingPort"), stdinLine = code, timeoutSec = 45)
        if (r.ok && r.msg.contains("Successfully paired", ignoreCase = true)) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_PAIRED, true).apply()
        }
        Log.i(TAG, "localadb pair($pairingPort) -> ${r.ok} ${r.msg}")
        return r
    }

    fun connect(ctx: Context, connectPort: Int): Res {
        startServer(ctx)
        val r = run(ctx, listOf("connect", "127.0.0.1:$connectPort"), timeoutSec = 30)
        val ok = r.msg.contains("connected to", ignoreCase = true)
        if (ok) savePort(ctx, connectPort, true)
        Log.i(TAG, "localadb connect($connectPort) -> $ok ${r.msg}")
        return Res(ok, r.msg)
    }

    /** Connect using the last known port, or the one mDNS advertised. */
    fun ensureConnected(ctx: Context): Res {
        if (!available(ctx)) return Res(false, "libadb.so not packaged")
        val candidates = listOfNotNull(port(ctx).takeIf { it > 0 }, discoveredConnectPort)
            .distinct()
        if (candidates.isEmpty()) return Res(false, "no adb port — open Wireless debugging, or pair once")
        var last = Res(false, "no port")
        for (p in candidates) {
            val r = connect(ctx, p)
            if (r.ok) return r
            last = r
        }
        return last
    }

    /** The privileged call: `adb -s 127.0.0.1:<port> shell <cmd>`. */
    fun shell(ctx: Context, cmd: String): Res {
        val p = port(ctx)
        if (p <= 0) return Res(false, "not connected (no port)")
        val r = run(ctx, listOf("-s", "127.0.0.1:$p", "shell", cmd), timeoutSec = 40)
        if (!r.ok && r.msg.contains("device", ignoreCase = true) && r.msg.contains("not found", true)) {
            // transport died: reconnect once and retry
            if (connect(ctx, p).ok) return run(ctx, listOf("-s", "127.0.0.1:$p", "shell", cmd), timeoutSec = 40)
        }
        return r
    }

    /**
     * Seed our adb key from a file the user placed in the app's external files
     * dir (pushable over adb, or shareable from a PC that is already trusted).
     *
     * Needed because some OEM builds (Samsung OneUI) do not draw app overlays on
     * top of Settings, which makes the floating pairing bar useless there — the
     * system pairing server dies as soon as the dialog loses foreground, so the
     * overlay was the only way to type the code without leaving it.
     */
    fun importKey(ctx: Context, srcKey: File, srcPub: File): Res {
        if (!srcKey.exists() || !srcPub.exists()) return Res(false, "key files not found")
        return try {
            val home = home(ctx)
            val dir = File(home, ".android").apply { mkdirs() }
            // adb looks for exactly `adbkey` / `adbkey.pub` in $HOME/.android
            val dstKey = File(dir, "adbkey")
            val dstPub = File(dir, "adbkey.pub")
            srcKey.copyTo(dstKey, overwrite = true)
            srcPub.copyTo(dstPub, overwrite = true)
            // adb demands 0600 on the private key or it regenerates a fresh one
            dstKey.setReadable(false, false); dstKey.setWritable(false, false); dstKey.setExecutable(false, false)
            dstKey.setReadable(true, true); dstKey.setWritable(true, true)
            // the running server caches its key at start-up, so restart it
            execRaw(ctx, listOf("kill-server"), null, 15)
            val p = execRaw(ctx, listOf("start-server"), null, 25)
            Res(p.ok, "imported (${srcKey.length()} bytes) · ${p.msg}")
        } catch (t: Throwable) {
            Res(false, "import failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Raw exec without the canned ok/failed heuristics. */
    private fun execRaw(ctx: Context, args: List<String>, stdinLine: String?, timeoutSec: Long): Res {
        val bin = binary(ctx)
        if (!bin.exists()) return Res(false, "libadb.so missing")
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", home(ctx).absolutePath)
                put("TMPDIR", ctx.cacheDir.absolutePath)
                put("LD_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir)
            }
            val pr = pb.start()
            if (stdinLine != null) {
                try { pr.outputStream.write((stdinLine + "\n").toByteArray()); pr.outputStream.flush() } catch (t: Throwable) {}
            }
            try { pr.outputStream.close() } catch (t: Throwable) {}
            val out = try { pr.inputStream.bufferedReader().readText() } catch (t: Throwable) { "" }
            if (!pr.waitFor(timeoutSec, TimeUnit.SECONDS)) { try { pr.destroyForcibly() } catch (t: Throwable) {}; return Res(false, "timeout") }
            Res(pr.exitValue() == 0, squash(out))
        } catch (t: Throwable) { Res(false, "exec failed: ${t.message}") }
    }

    /** Remember a connect port the user read off the Wireless debugging screen. */
    fun setConnectPort(ctx: Context, p: Int) = savePort(ctx, p, isPaired(ctx) || true)

    /** Wireless-debugging connect port: mDNS first, then the remembered one. */
    fun detectConnectPort(ctx: Context): Int =
        discover(ctx, 4000).connect ?: port(ctx).takeIf { it > 0 } ?: 0

    fun probe(ctx: Context): String {
        if (!available(ctx)) return "libadb.so not packaged"
        val v = run(ctx, listOf("version"), timeoutSec = 20)
        val sh = shell(ctx, "id")
        return "adb=${v.msg.take(60)} · shell=${if (sh.ok) sh.msg.take(80) else "not connected"}"
    }

    // --------------------------------------------------------------- discovery

    /**
     * Find the wireless-debugging ports via mDNS (the same services the system
     * advertises). `_adb-tls-connect` is always up while wireless debugging is
     * on; `_adb-tls-pairing` exists only while the pairing dialog is open.
     */
    fun discover(ctx: Context, timeoutMs: Long = 4000): Ports {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return Ports(port(ctx).takeIf { it > 0 }, null)
        val d1 = discoverService(nsd, SVC_CONNECT, timeoutMs) { discoveredConnectPort = it }
        val d2 = discoverService(nsd, SVC_PAIRING, timeoutMs) { discoveredPairingPort = it }
        return Ports(
            discoveredConnectPort ?: port(ctx).takeIf { it > 0 },
            discoveredPairingPort ?: if (d1 == d2) null else null
        )
    }

    private fun discoverService(
        nsd: NsdManager,
        type: String,
        timeoutMs: Long,
        onFound: (Int) -> Unit
    ): Int? {
        val found = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        var listener: NsdManager.DiscoveryListener? = null
        try {
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    latch.countDown()
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStopped(serviceType: String?) { latch.countDown() }
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(info: NsdServiceInfo?) {
                    if (info == null || info.port <= 0) return
                    if (found.get() < 0) {
                        found.set(info.port)
                        onFound(info.port)
                        latch.countDown()
                    }
                }
            }
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "localadb: mdns discovery failed for $type", t)
        } finally {
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (t: Throwable) {}
        }
        return found.get().takeIf { it > 0 }
    }
}
