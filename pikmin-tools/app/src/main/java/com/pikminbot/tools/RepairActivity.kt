package com.pikminbot.tools

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * In-app mock-location repair screen.
 *
 * Opened automatically by the notification SelfHeal posts, or by hand from
 * MainActivity ("Mock location repair"). It:
 *
 *  - shows why injection is failing (appop vs selection vs developer options)
 *  - runs the automatic repair chain on entry and on every return from Settings
 *  - deep-links to Developer options with the exact 3 taps to perform
 *  - watches the slot once per second and closes itself the moment the slot is
 *    healthy again, re-arming the last engine request automatically
 *
 * The engine service keeps running in degraded mode while this screen is up,
 * so no pin/jog has to be re-entered after a repair.
 */
class RepairActivity : Activity() {

    private val TAG = "PikminBotTools"
    private lateinit var status: TextView
    private lateinit var steps: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var wasBroken = false
    private var closed = false

    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Mock location repair"
            textSize = 20f
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        steps = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
            text = "Why this happens: switching Developer options off and on clears " +
                "Android's mock-location slot, so injection is refused even though the " +
                "settings screen still shows this app selected.\n\n" +
                "Manual fix (3 taps):\n" +
                "  1. Open Developer options\n" +
                "  2. Select mock location app\n" +
                "  3. Choose PikminBot Tools\n\n" +
                "This screen re-checks every second and re-arms the engine the moment " +
                "the slot is yours again."
        }
        root.addView(steps)

        root.addView(Button(this).apply {
            text = "Auto-repair now"
            setOnClickListener {
                val ok = SelfHeal.attemptRepair(this@RepairActivity, "RepairActivity")
                Toast.makeText(this@RepairActivity,
                    if (ok) "Repaired" else "Needs Developer options selection",
                    Toast.LENGTH_SHORT).show()
                render()
            }
        })

        root.addView(Button(this).apply {
            text = "Open Developer options"
            setOnClickListener { openDevOptions() }
        })

        root.addView(Button(this).apply {
            text = "Test injection"
            setOnClickListener {
                if (!SelfHeal.appopAllowed(this@RepairActivity)) {
                    Toast.makeText(this@RepairActivity, "Slot still not ours", Toast.LENGTH_SHORT).show()
                } else {
                    EngineService.rearm(this@RepairActivity)
                    Toast.makeText(this@RepairActivity, "Engine re-armed", Toast.LENGTH_SHORT).show()
                }
                render()
            }
        })

        root.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        Slothook()
        wasBroken = !SelfHeal.slotOk(this)
        // Try the automatic tiers immediately (root / Shizuku / setting).
        try { SelfHeal.attemptRepair(this, "RepairActivity.create") } catch (t: Throwable) {}
        render()
        handler.postDelayed(poll, 1000L)
    }

    /** React to SlotWatch events too, so a Settings trip re-verifies instantly. */
    private fun Slothook() {
        SlotEvents.subscribe { ev ->
            Log.i(TAG, "RepairActivity saw slot event: $ev")
            handler.post { render() }
        }
    }

    private fun openDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (t2: Throwable) {}
        }
    }

    private fun render() {
        if (closed) return
        val appopOk = SelfHeal.appopAllowed(this)
        val selected = SelfHeal.isMockAppSelected(this)
        val ok = appopOk && selected
        val dev = try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (t: Throwable) { false }

        status.text = buildString {
            append(if (ok) "STATUS: READY\n" else "STATUS: BLOCKED\n")
            append("Developer options: ${if (dev) "on" else "OFF"}\n")
            append("MOCK_LOCATION appop: ${if (appopOk) "allow" else "DENY"}\n")
            append("Selected app: ")
            append(when {
                selected -> "PikminBot Tools (ok)"
                else -> {
                    val s = try { Settings.Secure.getString(contentResolver, "mock_location") ?: "(none)" }
                    catch (t: Throwable) { "?" }
                    "$s (not us)"
                }
            })
            append("\n")
            append("Auto-repair channel: ")
            append(when {
                SelfHeal.shizukuAvailable() -> "Shizuku running"
                else -> "none (no root, Shizuku not running) — Developer options needed"
            })
            append("\nEngine: ${if (EngineService.running) "${EngineService.mode}${if (EngineService.degraded) " (degraded)" else ""}" else "idle"}")
            if (SelfHeal.totalAttempts > 0) {
                append("\nRepair attempts: ${SelfHeal.totalAttempts} · last: ${SelfHeal.lastRepair.ifBlank { "none" }}")
            }
            if (SelfHeal.lastEnvChange.isNotBlank()) append("\nLast environment change: ${SelfHeal.lastEnvChange}")
        }
        status.setTextColor(if (ok) Color.rgb(0, 120, 0) else Color.rgb(180, 0, 0))

        if (ok) {
            // Slot is ours again: re-arm whatever the engine was doing and go away.
            SelfHeal.clearFixNotification(this)
            if (wasBroken) {
                EngineService.rearm(this)
                EngineService.degraded = false
                Toast.makeText(this, "Mock location restored — engine re-armed", Toast.LENGTH_LONG).show()
                closed = true
                handler.postDelayed({ finish() }, 1200L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Developer options — re-verify at once.
        handler.postDelayed({ try {
            if (!SelfHeal.slotOk(this)) SelfHeal.attemptRepair(this, "RepairActivity.resume")
            render()
        } catch (t: Throwable) {} }, 400L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
