package com.pikminbot.hcsteps

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.coroutines.runBlocking

/**
 * Health Connect + Google Fit Step Injector (dual pathway).
 *
 * Headless mode (for ADB automation — keeps inject_steps.py working):
 *   am start -n com.pikminbot.hcsteps/.MainActivity --ei count 5000 --ei minutes 60
 *   am start -n com.pikminbot.hcsteps/.MainActivity --ei count 5000 --ez fit true    # Fit cloud only
 *   am start -n com.pikminbot.hcsteps/.MainActivity --ei count 5000 --ez both true   # HC + Fit cloud
 *   am start -n com.pikminbot.hcsteps/.MainActivity --ez verify true          # read back to logcat
 *   am start -n com.pikminbot.hcsteps/.MainActivity --ez delete true          # delete mine to logcat
 *
 * Interactive mode: launch from the launcher for the full UI
 * (presets, custom count, date/time picker, duration, chunk size, verify, delete, Fit pathway).
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var fitStatusView: TextView
    private lateinit var logView: TextView
    private lateinit var countInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var chunkInput: EditText
    private lateinit var startTimeView: TextView
    private var selectedStart: LocalDateTime? = null

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleHeadless(intent)) return
        // ── Interactive UI ───────────────────────────────────────
        setContentView(buildUi())
        refreshStatus()
        // if permissions missing, ask once on open
        if (!StepWriter.hasPermissions(this)) {
            StepWriter.requestPermissions(this)
        }
    }

    /** Handles headless commands; returns true when the activity should finish. */
    private fun handleHeadless(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false

        if (extras.containsKey("count")) {
            val count = extras.getInt("count", 5000)
            val minutes = extras.getInt("minutes", 60)
            val startMs = extras.getLong("start_epoch", -1L)
            val chunk = extras.getInt("chunk_minutes", 15)
            val end = if (startMs > 0) Instant.ofEpochMilli(startMs).plusSeconds(minutes * 60L) else Instant.now()
            val doFit = extras.getBoolean("fit", false) || extras.getBoolean("both", false)
            val doHc = !extras.getBoolean("fit", false) || extras.getBoolean("both", false)
            Log.i("HCStepWriter", "Headless inject $count steps over $minutes min chunk=$chunk end=$end hc=$doHc fit=$doFit")
            val results = StringBuilder()
            if (doHc) results.append(StepWriter.inject(this, count, minutes, end, chunk))
            if (doFit) {
                if (results.isNotEmpty()) results.append(" | ")
                results.append(runBlocking { FitWriter.inject(this@MainActivity, count, minutes, chunk) })
            }
            Log.i("HCStepWriter", "Result: $results")
            Toast.makeText(this, "Inject: $results", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        if (extras.getBoolean("verify", false)) {
            val (from, to) = todayWindow()
            val chunks = StepWriter.verify(this, from, to)
            val sb = StringBuilder("VERIFY ${chunks.size} own records (${from}..${to})\n")
            var total = 0L
            chunks.forEach { c ->
                total += c.steps
                sb.append("  ${fmtLocal(c.start)} - ${fmtLocal(c.end)}: ${c.steps} steps id=${c.recordId}\n")
            }
            sb.append("TOTAL own steps: $total")
            Log.i("HCStepWriter", sb.toString())
            Toast.makeText(this, "Verify: $total own steps in ${chunks.size} records", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        if (extras.getBoolean("delete", false)) {
            val (from, to) = todayWindow()
            val n = StepWriter.deleteMine(this, from, to)
            Log.i("HCStepWriter", "DELETE: removed $n own records")
            Toast.makeText(this, "Deleted $n records", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleHeadless(intent)) return
        // UI already showing; nothing else to do
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == StepWriter.REQ_PERMISSIONS) {
            refreshStatus()
            val granted = StepWriter.hasPermissions(this)
            appendLog("Permission screen closed (resultCode=$resultCode, granted=$granted)")
        }
    }

    private fun buildUi(): ViewGroup {
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        fun label(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, dp(12), 0, dp(2))
        }

        col.addView(TextView(this).apply {
            text = "Health Connect Step Injector"
            textSize = 20f
            gravity = Gravity.CENTER
        })

        statusView = TextView(this).apply { textSize = 13f }
        col.addView(statusView)

        col.addView(label("Step count"))
        countInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("10000")
            hint = "e.g. 10000"
        }
        col.addView(countInput)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("1,000", "10,000", "50,000").forEach { p ->
            presets.addView(Button(this).apply {
                text = p
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                setOnClickListener { countInput.setText(p.replace(",", "")) }
            })
        }
        col.addView(presets)

        col.addView(label("Start time"))
        val startRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startTimeView = TextView(this).apply { text = "Now (ends at current time)" }
        startRow.addView(startTimeView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        startRow.addView(Button(this).apply {
            text = "Set"
            setOnClickListener { pickStartTime() }
        })
        col.addView(startRow)

        col.addView(label("Duration (minutes, ending at start+duration)"))
        durationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("60")
        }
        col.addView(durationInput)

        col.addView(label("Chunk size (minutes per record)"))
        chunkInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("15")
        }
        col.addView(chunkInput)

        col.addView(Button(this).apply {
            text = "Inject steps"
            setOnClickListener { onInject() }
        })
        col.addView(Button(this).apply {
            text = "Test 1,000 steps (now)"
            setOnClickListener {
                val r = StepWriter.inject(this@MainActivity, 1000, 30, Instant.now(), 10)
                appendLog(r)
            }
        })

        // ── Google Fit pathway (REST cloud → Fit app sync → game) ────────
        col.addView(label("Fit pathway (cloud REST — game reads Fit)"))
        fitStatusView = TextView(this).apply { textSize = 12f }
        col.addView(fitStatusView)
        val fitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fitRow.addView(Button(this).apply {
            text = "Fit cloud only"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onInjectFit() }
        })
        fitRow.addView(Button(this).apply {
            text = "BOTH (HC + Fit)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onInjectBoth() }
        })
        col.addView(fitRow)
        col.addView(TextView(this).apply {
            text = "Fit always writes the last N minutes ending now (start-time picker ignored)."
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        })

        col.addView(Button(this).apply {
            text = "Verify (read back mine)"
            setOnClickListener { onVerify() }
        })
        col.addView(Button(this).apply {
            text = "Delete my injected records"
            setOnClickListener { onDelete() }
        })

        col.addView(label("Log"))
        logView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        col.addView(logView)

        return root
    }

    private fun pickStartTime() {
        val now = LocalDateTime.now()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                selectedStart = LocalDateTime.of(y, m + 1, d, h, min)
                startTimeView.text = "Start: ${selectedStart!!.format(fmt)}"
            }, now.hour, now.minute, true).show()
        }, now.year, now.monthValue - 1, now.dayOfMonth).show()
    }

    private fun onInject() {
        val count = countInput.text.toString().toIntOrNull() ?: run { toast("bad count"); return }
        val minutes = durationInput.text.toString().toIntOrNull() ?: 30
        val chunk = chunkInput.text.toString().toIntOrNull() ?: 15
        val start = selectedStart?.atZone(ZoneId.systemDefault())?.toInstant()
        val end = if (start != null) start.plusSeconds(minutes * 60L) else Instant.now()
        val result = StepWriter.inject(this, count, minutes, end, chunk)
        appendLog(result)
        toast(result)
    }

    private fun currentParams(): Triple<Int, Int, Int> {
        val count = countInput.text.toString().toIntOrNull() ?: 5000
        val minutes = durationInput.text.toString().toIntOrNull() ?: 30
        val chunk = chunkInput.text.toString().toIntOrNull() ?: 15
        return Triple(count, minutes, chunk)
    }

    private fun onInjectFit() {
        val (count, minutes, chunk) = currentParams()
        val result = runBlocking { FitWriter.inject(this@MainActivity, count, minutes, chunk) }
        appendLog("[FIT] $result")
        toast(result)
    }

    private fun onInjectBoth() {
        val (count, minutes, chunk) = currentParams()
        val start = selectedStart?.atZone(ZoneId.systemDefault())?.toInstant()
        val end = if (start != null) start.plusSeconds(minutes * 60L) else Instant.now()
        val hc = StepWriter.inject(this, count, minutes, end, chunk)
        appendLog("[HC] $hc")
        val fit = runBlocking { FitWriter.inject(this@MainActivity, count, minutes, chunk) }
        appendLog("[FIT] $fit")
        toast("HC: $hc | FIT: $fit")
    }

    private fun onVerify() {
        val (from, to) = todayWindow()
        val chunks = StepWriter.verify(this, from, to)
        val sb = StringBuilder("Own records (${fmtLocal(from)} .. ${fmtLocal(to)}): ${chunks.size}\n")
        var total = 0L
        chunks.forEach { c ->
            total += c.steps
            sb.append("  ${fmtLocal(c.start)} - ${fmtLocal(c.end)}: ${c.steps} steps (${c.clientRecordId})\n")
        }
        sb.append("TOTAL: $total steps")
        appendLog(sb.toString())
    }

    private fun onDelete() {
        val (from, to) = todayWindow()
        val n = StepWriter.deleteMine(this, from, to)
        appendLog(if (n >= 0) "Deleted $n own records" else "Delete failed (see logcat)")
        toast("Deleted $n records")
    }

    private fun todayWindow(): Pair<Instant, Instant> {
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).atZone(zone).toInstant()
        val end = Instant.now().plusSeconds(60)
        return start to end
    }

    private fun refreshStatus() {
        val status = StepWriter.sdkStatus(this)
        val perm = StepWriter.hasPermissions(this)
        statusView.text = "HC SDK: $status (${if (status == HealthConnectClient.SDK_AVAILABLE) "available" else "UNAVAILABLE"}) | WRITE_STEPS granted: $perm"
        if (::fitStatusView.isInitialized) {
            fitStatusView.text = "Fit: ${if (FitWriter.hasToken(this)) "token OK" else "NO fit_token.json — provision via ADB"}"
        }
    }

    private fun appendLog(s: String) {
        logView.text = logView.text.toString().let { if (it.isBlank()) s else it + "\n" + s }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmtLocal(i: Instant) = LocalDateTime.ofInstant(i, ZoneId.systemDefault()).format(fmt)
}
