package com.pikminbot.hcsteps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Inject steps without opening UI:
 *   am broadcast -a com.pikminbot.INJECT_STEPS --ei count 8000 --ei minutes 60 [--el start_epoch <ms>] [--ei chunk_minutes 15]
 *   am broadcast -a com.pikminbot.INJECT_STEPS --ei count 8000 --ez both true   # HC + Fit cloud
 */
class StepInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val count = intent.getIntExtra("count", 5000)
        val minutes = intent.getIntExtra("minutes", 60)
        val startMs = intent.getLongExtra("start_epoch", -1L)
        val chunk = intent.getIntExtra("chunk_minutes", 15)
        val end = if (startMs > 0) Instant.ofEpochMilli(startMs).plusSeconds(minutes * 60L) else Instant.now()
        val doFit = intent.getBooleanExtra("fit", false) || intent.getBooleanExtra("both", false)
        val doHc = !intent.getBooleanExtra("fit", false) || intent.getBooleanExtra("both", false)
        Log.i("HCStepWriter", "Broadcast inject $count steps over $minutes min chunk=$chunk hc=$doHc fit=$doFit")
        val results = StringBuilder()
        if (doHc) results.append(StepWriter.inject(context, count, minutes, end, chunk))
        if (doFit) {
            if (results.isNotEmpty()) results.append(" | ")
            results.append(runBlocking { FitWriter.inject(context, count, minutes, chunk) })
        }
        Log.i("HCStepWriter", "Result: $results")
    }
}
