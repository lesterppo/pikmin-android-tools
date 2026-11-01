package com.pikminbot.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Adb / automation entry point for mock time (v2.5).
 *
 *   adb shell am broadcast -n com.pikminbot.tools/.MockTimeReceiver \
 *     -a com.pikminbot.mock.SET_TIME --el epoch 1793631837000 --es tz Asia/Tokyo
 *   adb shell am broadcast -n com.pikminbot.tools/.MockTimeReceiver \
 *     -a com.pikminbot.mock.SET_TIME --ef shift_hours 24
 *   adb shell am broadcast -n com.pikminbot.tools/.MockTimeReceiver \
 *     -a com.pikminbot.mock.RESTORE_TIME
 *
 * The broadcast only *requests* the change — it executes through the same
 * privileged ladder as the UI (root -> Shizuku), because SET_TIME is a
 * signature permission no app can hold. Always send the explicit -n component:
 * Android 14+ does not deliver implicit broadcasts to another app's manifest
 * receiver.
 */
class MockTimeReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "PikminBotTools"
        const val ACTION_SET = "com.pikminbot.mock.SET_TIME"
        const val ACTION_RESTORE = "com.pikminbot.mock.RESTORE_TIME"
        const val ACTION_STATUS = "com.pikminbot.mock.TIME_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val pending = goAsync()
        Thread {
            val out = try {
                when (action) {
                    ACTION_SET -> {
                        if (intent.getBooleanExtra("restore", false)) {
                            MockTime.restore(app)
                        } else {
                            val tz = intent.getStringExtra("tz")
                            val shift = num(intent, "shift_hours")
                            val epoch = num(intent, "epoch")
                            when {
                                shift != null -> MockTime.shift(app, shift)
                                else -> MockTime.applyMock(
                                    app,
                                    epoch?.toLong(),
                                    tz?.takeIf { it.isNotBlank() }
                                )
                            }
                        }
                    }
                    ACTION_RESTORE -> MockTime.restore(app)
                    ACTION_STATUS -> "tier=${MockTime.tierName()} active=${MockTime.isActive(app)} " +
                            MockTime.snapshot(app)
                    else -> "unknown action $action"
                }
            } catch (t: Throwable) {
                "ERROR ${t.javaClass.simpleName}: ${t.message}"
            }
            Log.i(TAG, "mocktime receiver [$action] -> $out")
            pending.finish()
        }.start()
    }

    /** Extras arrive as Float/Double/Long/Int/String depending on the adb flag
     *  used (--ef / --ed / --el / --ei / --es); Bundle typed getters return a
     *  default instead of throwing on a mismatch, so probe the raw value. */
    private fun num(i: Intent, key: String): Double? {
        val v: Any? = try { i.extras?.get(key) } catch (t: Throwable) { null }
        return (v as? Number)?.toDouble()
    }
}
