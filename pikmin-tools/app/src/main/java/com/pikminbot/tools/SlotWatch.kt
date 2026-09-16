package com.pikminbot.tools

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Watches the two things that silently break mock-location injection:
 *
 *  1. Development options being switched off and on again. Android resets the
 *     "Select mock location app" slot on that transition, which drops the
 *     MOCK_LOCATION appop back to its default (deny) while the stored
 *     `mock_location` setting can still name this package. The UI therefore
 *     looks correct while every injection throws SecurityException.
 *  2. The `mock_location` setting itself changing (another companion app, or
 *     the user, taking the single slot).
 *
 * A ContentObserver cannot see an appop change directly (appops are not in
 * Settings), so a 3 s poll runs alongside the observers as a backstop. While
 * the engine service is alive this makes the app notice a developer-mode
 * toggle within a few seconds and repair itself, instead of waiting for the
 * next addTestProvider failure.
 *
 * Callers register one listener; SlotWatch owns the thread + observers.
 */
object SlotWatch {

    private const val TAG = "PikminBotTools"
    private const val POLL_MS = 3000L

    /** Fired with a short reason string whenever the environment changes. */
    var listener: ((String) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private var appCtx: Context? = null

    private var lastDev: Int = -1
    private var lastSel: String = ""
    private var lastHadSlot = true

    val devOptionsEnabled: Boolean
        get() = try {
            Settings.Global.getInt(appCtx?.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (t: Throwable) { false }

    // Refcounted: the Activity and the EngineService both watch, and whichever
    // is destroyed last owns the teardown (otherwise closing the UI would kill
    // the service's watcher).
    private var refs = 0

    fun start(ctx: Context) {
        refs++
        if (handler != null) return
        val c = ctx.applicationContext
        appCtx = c
        val t = HandlerThread("slotwatch").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        // Seed so the first poll does not fire a spurious "changed" event.
        lastDev = if (devOptionsEnabled) 1 else 0
        lastSel = selectedPackage()
        val obs = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                check("settings changed")
            }
        }
        observer = obs
        try {
            c.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false, obs)
            c.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("mock_location"), false, obs)
        } catch (t2: Throwable) {
            Log.e(TAG, "SlotWatch: observer registration failed", t2)
        }
        val poll = object : Runnable {
            override fun run() {
                check("poll")
                handler?.postDelayed(this, POLL_MS)
            }
        }
        h.postDelayed(poll, POLL_MS)
        Log.i(TAG, "SlotWatch started (dev=${lastDev == 1}, sel=$lastSel)")
    }

    fun stop() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs > 0) return
        observer?.let {
            try { appCtx?.contentResolver?.unregisterContentObserver(it) } catch (t: Throwable) {}
        }
        observer = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "SlotWatch stopped")
    }

    /** Fresh check, fired from the poll + observers. */
    private fun check(reason: String) {
        val dev = if (devOptionsEnabled) 1 else 0
        val sel = selectedPackage()
        val devChanged = lastDev != -1 && dev != lastDev
        val selChanged = sel != lastSel
        lastDev = dev
        lastSel = sel
        val haveSlot = SelfHeal.slotOk(appCtx)
        if (devChanged) {
            Log.w(TAG, "SlotWatch: developer options ${if (dev == 1) "ON" else "OFF"}")
            SelfHeal.noteEnvChange("developer options ${if (dev == 1) "on" else "off"}")
            SlotEvents.emit("dev-mode ${if (dev == 1) "on" else "off"}")
        }
        if (selChanged) {
            Log.w(TAG, "SlotWatch: mock_location selection -> '$sel' (us=${appCtx?.packageName})")
            SelfHeal.noteEnvChange("selection changed")
            SlotEvents.emit("selection=$sel")
        }
        // A lost slot is always worth reporting, even when nothing "changed"
        // (the appop can drop with the setting left untouched).
        // Emit only on the transition — the 3 s poll would otherwise repeat it.
        if (!haveSlot && lastHadSlot) SlotEvents.emit("slot-lost:$reason")
        if (haveSlot && !lastHadSlot) SlotEvents.emit("slot-restored:$reason")
        lastHadSlot = haveSlot
    }

    private fun selectedPackage(): String = try {
        Settings.Secure.getString(appCtx?.contentResolver, "mock_location") ?: ""
    } catch (t: Throwable) { "" }
}

/** Tiny in-process event fan-out so UI + service can both react. */
object SlotEvents {
    private val handlers = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    fun subscribe(h: (String) -> Unit) { handlers.add(h) }
    fun unsubscribe(h: (String) -> Unit) { handlers.remove(h) }
    fun emit(ev: String) {
        for (h in handlers) {
            try { h(ev) } catch (t: Throwable) {}
        }
        SlotWatch.listener?.let { try { it(ev) } catch (t: Throwable) {} }
    }
}
