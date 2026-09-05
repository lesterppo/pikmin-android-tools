package com.pikminbot.tools

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * PikminBot Tools control screen — TWO tabs at the top of the start page so the
 * user picks MockLoc or Jogger (combined in one unified EngineService v2.0+):
 *
 *   [ MockLoc ] [ Jogger ]           <- tabs
 *
 * MockLoc tab:  embedded Leaflet map, tap/drag to pick a point, lat/lon fields,
 *               Inject toggle (ON = pin persistently; OFF = release).
 * Jogger tab:   route (loop/straight), speed, steps/s, radius, heading,
 *               duration, Start/Stop, HC permission + verify helpers.
 *
 * Both drive the single EngineService (live pin<->jog mode switch), so there is
 * exactly one mock GPS provider owner — no intra-app conflict.
 */
class MainActivity : Activity() {

    private lateinit var statusTab: TextView
    private lateinit var mockContent: LinearLayout
    private lateinit var jogContent: LinearLayout
    private lateinit var engineStatus: TextView
    private lateinit var healNote: TextView
    private val poll = Handler(Looper.getMainLooper())

    // Shared poll so both tabs stay live no matter which is visible.
    private val pollTask = object : Runnable {
        override fun run() {
            refreshEngineStatus()
            poll.postDelayed(this, 500)
        }
    }

    // --------------------------- mockloc tab fields --------------------------
    private lateinit var latIn: EditText
    private lateinit var lonIn: EditText
    private lateinit var pinStatus: TextView
    private lateinit var injectToggle: Switch
    private lateinit var map: WebView

    // --------------------------- jogger tab fields ---------------------------
    private lateinit var jogStatus: TextView
    private lateinit var jogLog: TextView
    private lateinit var modeLabel: TextView
    private lateinit var loopBtn: Button
    private lateinit var straightBtn: Button
    private lateinit var radiusInput: EditText
    private lateinit var headingInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var stepsInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var jogStartBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "PikminBot Tools"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })

        // ---- tab selector -------------------------------------------------
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mockTabBtn = tabButton("MockLoc")
        val jogTabBtn = tabButton("Jogger")
        tabRow.addView(mockTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        tabRow.addView(jogTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabRow)

        statusTab = TextView(this).apply {
            text = "Engine: idle"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(statusTab)

        // MockLoc tab content
        mockContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buildMockLocPanel(mockContent)
        root.addView(mockContent)

        // Jogger tab content
        jogContent = buildJoggerPanel()
        jogContent.visibility = View.GONE
        root.addView(jogContent)

        // engine-level status + self-heal shared footer
        engineStatus = TextView(this).apply { text = "Engine: idle"; textSize = 12f; setPadding(0, dp(4), 0, 0) }
        root.addView(engineStatus)
        healNote = TextView(this).apply { setTextColor(Color.rgb(150, 60, 0)); textSize = 12f }
        root.addView(healNote)

        setContentView(root)

        mockTabBtn.setOnClickListener { selectTab("mockloc") }
        jogTabBtn.setOnClickListener { selectTab("jogger") }
        selectTab("mockloc")

        requestLocationPerms()
        refreshJogStatus()  // init HC hint
        poll.postDelayed(pollTask, 400)
        handleIntent(intent)
    }

    private fun tabButton(name: String): Button = Button(this).apply { text = name }

    private fun selectTab(which: String) {
        val mock = which == "mockloc"
        mockContent.visibility = if (mock) View.VISIBLE else View.GONE
        jogContent.visibility = if (mock) View.GONE else View.VISIBLE
        if (mock) refreshPinStatus()
        else { refreshJogStatus(); if (!::jogLog.isInitialized) {} }
    }

    // ================================================================= MAP TAB
    private fun buildMockLocPanel(parent: LinearLayout) {
        val pad = dp(8)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        latIn = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Lat"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
        }
        lonIn = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Lon"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(latIn)
        row.addView(lonIn)
        parent.addView(row)

        val goRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pinStatus = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "Idle"
            textSize = 13f
        }
        goRow.addView(pinStatus)
        injectToggle = Switch(this).apply { text = "Inject" }
        injectToggle.setOnCheckedChangeListener { _, on ->
            if (on) startPollPin()
            else EngineService.stop(this)
        }
        goRow.addView(injectToggle)
        parent.addView(goRow)

        Button(this).apply {
            text = "Apply coordinates"
            setOnClickListener { applyMockLocImpl() }
        }.also { parent.addView(it) }

        map = WebView(this)
        map.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        setupMap()
        parent.addView(map)

        // init fields
        val curLat = if (EngineService.curLat != 0.0) EngineService.curLat else 22.3193
        val curLon = if (EngineService.curLon != 0.0) EngineService.curLon else 114.1694
        latIn.setText(String.format(Locale.US, "%.6f", curLat))
        lonIn.setText(String.format(Locale.US, "%.6f", curLon))
    }

    private fun setupMap() {
        val ws = map.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        map.webViewClient = WebViewClient()
        map.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                if (m != null) Log.d("ToolsMap", m.message())
                return true
            }
        }
        map.addJavascriptInterface(MapBridge(), "tools")

        val html = "<!DOCTYPE html><html><head><meta name='viewport' " +
            "content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>" +
            "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>" +
            "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>" +
            "<style>html,body{margin:0;height:100%;}#map{position:absolute;top:0;bottom:0;left:0;right:0;}</style>" +
            "</head><body><div id='map'></div><script>" +
            "var map=L.map('map',{zoomControl:true}).setView([22.3193,114.1694],15);" +
            "L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'&copy; Esri'}).addTo(map);" +
            "var marker=L.marker([22.3193,114.1694],{draggable:true}).addTo(map);" +
            "function emit(lat,lng){if(window.tools)tools.onPick(lat,lng);}" +
            "map.on('click',function(e){marker.setLatLng(e.latlng);emit(e.latlng.lat,e.latlng.lng);});" +
            "marker.on('dragend',function(e){var ll=e.target.getLatLng();emit(ll.lat,ll.lng);});" +
            "window.setMarker=function(lat,lng){marker.setLatLng([lat,lng]);map.setView([lat,lng],15);};" +
            "</script></body></html>"
        map.loadDataWithBaseURL("https://openstreetmap.org/", html, "text/html", "UTF-8", null)
    }

    private inner class MapBridge {
        @JavascriptInterface
        fun onPick(lat: Double, lon: Double) {
            runOnUiThread {
                latIn.setText(String.format(Locale.US, "%.6f", lat))
                lonIn.setText(String.format(Locale.US, "%.6f", lon))
                if (injectToggle.isChecked) {
                    val la = lat; val lo = lon
                    EngineService.startPin(this@MainActivity, la, lo, persistent = true)
                    Toast.makeText(this@MainActivity,
                        String.format(Locale.US, "Pinning %.6f, %.6f", la, lo), Toast.LENGTH_SHORT).show()
                } else refreshPinStatus()
            }
        }
    }

    private fun applyMockLocImpl() {
        val lat = parse(latIn.text.toString())
        val lon = parse(lonIn.text.toString())
        if (lat.isNaN() || lon.isNaN() || !isValidCoord(lat, lon)) {
            Toast.makeText(this, "Enter valid lat -90..90, lon -180..180", Toast.LENGTH_LONG).show()
            return
        }
        moveMapMarker(lat, lon)
        if (injectToggle.isChecked) EngineService.startPin(this, lat, lon, persistent = true)
        Toast.makeText(this, String.format(Locale.US, "Pin %.6f, %.6f (inject %s)",
            lat, lon, if (injectToggle.isChecked) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
        refreshPinStatus()
    }

    private fun startPollPin() {
        val lat = parse(latIn.text.toString())
        val lon = parse(lonIn.text.toString())
        if (lat.isNaN() || lon.isNaN() || !isValidCoord(lat, lon)) {
            injectToggle.isChecked = false
            Toast.makeText(this, "Enter valid coordinates first", Toast.LENGTH_LONG).show()
            return
        }
        moveMapMarker(lat, lon)
        // From the UI pin = persistent (map-coverage mode).
        EngineService.startPin(this, lat, lon, persistent = true)
    }

    private fun refreshPinStatus() {
        if (!::pinStatus.isInitialized) return
        pinStatus.text = if (EngineService.running && EngineService.mode == "pin") {
            String.format(Locale.US, "INJECTING\n%.6f, %.6f · %d ticks",
                EngineService.curLat, EngineService.curLon, EngineService.tickCount)
        } else "Idle — not pinning."
    }

    private fun moveMapMarker(lat: Double, lon: Double) {
        map?.evaluateJavascript("javascript:if(window.setMarker)setMarker($lat,$lon);", null)
    }

    // ================================================================= JOG TAB
    private fun buildJoggerPanel(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = 12

        fun label(t: String): TextView = TextView(this).apply {
            text = t; textSize = 14f; setPadding(0, dp(pad), 0, dp(2))
        }

        jogStatus = TextView(this).apply { textSize = 13f }
        col.addView(jogStatus)

        col.addView(label("Route mode"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loopBtn = Button(this).apply {
            text = "Loop"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { setJogMode("loop") }
        }
        straightBtn = Button(this).apply {
            text = "Straight"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { setJogMode("straight") }
        }
        modeRow.addView(loopBtn)
        modeRow.addView(straightBtn)
        col.addView(modeRow)
        modeLabel = TextView(this).apply { textSize = 12f }
        col.addView(modeLabel)

        col.addView(label("Speed (km/h)"))
        speedInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("${EngineService.jogSpeedKph}")
        }
        col.addView(speedInput)

        col.addView(label("Steps per second"))
        stepsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("${EngineService.jogStepsPerSec}")
        }
        col.addView(stepsInput)

        col.addView(label("Loop radius (m)  (straight uses this as distance adv. ignored)"))
        radiusInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("${EngineService.jogRadiusM.toInt()}")
        }
        col.addView(radiusInput)

        col.addView(label("Heading (deg: 0=N 90=E 180=S 270=W)"))
        headingInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("${EngineService.jogHeadingDeg.toInt()}")
        }
        col.addView(headingInput)

        col.addView(label("Duration (minutes)"))
        durationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("${EngineService.jogDurationMin}")
        }
        col.addView(durationInput)

        jogStartBtn = Button(this).apply { setOnClickListener { onJogStartStop() } }
        col.addView(jogStartBtn)

        col.addView(Button(this).apply {
            text = "Request permissions"
            setOnClickListener {
                requestLocationPerms()
                StepWriter.requestPermissions(this@MainActivity)
            }
        })
        col.addView(Button(this).apply {
            text = "Use current/MockLoc point as origin"
            setOnClickListener {
                val lat = if (EngineService.curLat != 0.0) EngineService.curLat else 22.3193
                val lon = if (EngineService.curLon != 0.0) EngineService.curLon else 114.1694
                latIn?.setText(String.format(Locale.US, "%.6f", lat))
                lonIn?.setText(String.format(Locale.US, "%.6f", lon))
                Toast.makeText(this@MainActivity, "Set as origin: $lat,$lon", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(Button(this).apply {
            text = "Temporarily pin at this point"
            setOnClickListener {
                val lat = EngineService.curLat
                val lon = EngineService.curLon
                EngineService.startPin(this@MainActivity, lat, lon, persistent = false)
                Toast.makeText(this@MainActivity, "Pinned temporarily", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(Button(this).apply {
            text = "Verify my HC records"
            setOnClickListener {
                val r = StepWriter.verify(this@MainActivity, todayStart(), java.time.Instant.now().plusSeconds(60))
                val total = r.sumOf { it.steps }
                appendLog("Own HC records: ${r.size}, $total steps (since midnight)")
            }
        })

        col.addView(label("Log"))
        jogLog = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, dp(8)) }
        col.addView(jogLog)
        return col
    }

    private fun setJogMode(m: String) {
        EngineService.jogMode = m
        refreshJogStatus()
    }

    private fun onJogStartStop() {
        if (EngineService.running && EngineService.mode == "jog") {
            EngineService.stop(this)
            appendLog("Stopping...")
            refreshJogStatus()
            return
        }
        val speed = speedInput.text.toString().toDoubleOrNull() ?: 10.0
        val steps = stepsInput.text.toString().toDoubleOrNull() ?: 2.0
        val radius = radiusInput.text.toString().toIntOrNull() ?: 100
        val heading = headingInput.text.toString().toIntOrNull() ?: 0
        val duration = durationInput.text.toString().toIntOrNull() ?: 30
        if (heading !in 0..360) { Toast.makeText(this, "Heading must be 0-360", Toast.LENGTH_LONG).show(); return }
        if (speed <= 0.0 || speed > 30.0) { Toast.makeText(this, "Speed 0.1-30 km/h", Toast.LENGTH_LONG).show(); return }
        if (steps < 0.0 || steps > 10.0) { Toast.makeText(this, "Steps/s must be 0-10", Toast.LENGTH_LONG).show(); return }
        var lat = if (latIn.text.isNotBlank()) parse(latIn.text.toString()) else EngineService.curLat
        var lon = if (lonIn.text.isNotBlank()) parse(lonIn.text.toString()) else EngineService.curLon
        if (lat.isNaN() || lon.isNaN()) { lat = EngineService.curLat; lon = EngineService.curLon }
        EngineService.startJog(this, lat, lon, EngineService.jogMode, speed, steps, radius.toDouble(), heading.toDouble(), duration.toLong())
        appendLog("Starting jog: mode=${EngineService.jogMode} $speed km/h heading=$heading steps=$steps/s radius=$radius dur=${duration}min")
    }

    private fun refreshJogStatus() {
        if (!::jogStatus.isInitialized) return
        val e = EngineService
        val hc = try { StepWriter.hasPermissions(this) } catch (t: Throwable) { false }
        if (e.running && e.mode == "jog") {
            val elapsedSec = (System.currentTimeMillis() - e.startEpochMs) / 1000
            jogStatus.text = "JOGGING · ${"%.1f".format(e.jogSpeedMpsView())} m/s " +
                "· ${"%.5f".format(e.curLat)}, ${"%.5f".format(e.curLon)}\n" +
                "Elapsed ${elapsedSec / 60}m${elapsedSec % 60}s · ${"%.0f".format(e.totalMeters)} m · ${e.totalSteps} steps\n" +
                "HC WRITE_STEPS granted: $hc"
            jogStartBtn.text = "Stop jog"
        } else {
            jogStartBtn.text = "Start jog"
            jogStatus.text = "Idle\nHC WRITE_STEPS granted: $hc" +
                (if (e.lastError.isNotBlank()) "\nLast error: ${e.lastError}" else "")
        }
        if (::modeLabel.isInitialized) {
            modeLabel.text = "Mode: ${e.jogMode} · radius ${e.jogRadiusM.toInt()} m · heading ${e.jogHeadingDeg.toInt()}"
            loopBtn.isSelected = e.jogMode == "loop"
            straightBtn.isSelected = e.jogMode == "straight"
        }
    }

    // ================================================================= shared
    private fun refreshEngineStatus() {
        if (!::statusTab.isInitialized) return
        val e = EngineService
        statusTab.text = if (e.running) {
            "Engine: ${e.mode.uppercase()} @ ${"%.5f".format(e.curLat)}, ${"%.5f".format(e.curLon)}"
        } else "Engine: idle"
        if (::engineStatus.isInitialized)
            engineStatus.text = if (e.running) {
                "mode=${e.mode} · ${e.tickCount} ticks · ${"%.0f".format(e.totalMeters)}m/${e.totalSteps} steps"
            } else "Engine: idle"
        healNote.text = if (SelfHeal.lastSecurityExceptionMs > 0L) {
            val ago = ((System.currentTimeMillis() - SelfHeal.lastSecurityExceptionMs) / 1000).coerceAtLeast(0)
            "SecurityException ${ago}s ago · self-heal ${SelfHeal.attempts}/3 · last repair: ${SelfHeal.lastRepair.ifBlank { "none" }}"
        } else "No SecurityException this session."
        if (EngineService.running && EngineService.mode == "jog") refreshJogStatus()
        else if (EngineService.running && EngineService.mode == "pin") refreshPinStatus()
    }

    private fun appendLog(s: String) {
        if (!::jogLog.isInitialized) return
        jogLog.text = jogLog.text.toString().let { if (it.isBlank()) s else it + "\n" + s }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val lat = readCoord(i, "lat", "latitude")
        val lon = readCoord(i, "lon", "lng", "longitude")
        if (i.getBooleanExtra(EXTRA_AUTOSTART, false) && !lat.isNaN() && !lon.isNaN()) {
            latIn?.setText(String.format(Locale.US, "%.6f", lat))
            lonIn?.setText(String.format(Locale.US, "%.6f", lon))
            moveMapMarker(lat, lon)
            EngineService.startPin(this, lat, lon, persistent = false)
            Toast.makeText(this, String.format(Locale.US, "Pinning %.6f, %.6f", lat, lon), Toast.LENGTH_SHORT).show()
        } else if (!i.getBooleanExtra(EXTRA_AUTOSTART, false) && !lat.isNaN() && !lon.isNaN()) {
            // plain coordinate intent: just move map + fill fields
            latIn?.setText(String.format(Locale.US, "%.6f", lat))
            lonIn?.setText(String.format(Locale.US, "%.6f", lon))
            moveMapMarker(lat, lon)
            if (injectToggle.isChecked) EngineService.startPin(this, lat, lon, persistent = true)
        }
        handleJogHeadless(i)
    }

    /** handle --ez start true / -es cmd stop extras for compat with old jogger main */
    private fun handleJogHeadless(i: Intent?) {
        if (i == null) return
        val extras = i.extras ?: return
        if (extras.getBoolean("start", false)) {
            startJogFromExtras(extras)
        } else if ("stop".equals(extras.getString("cmd"))) {
            EngineService.stop(this)
        }
    }

    private fun startJogFromExtras(extras: Bundle) {
        val mode = extras.getString("mode") ?: "loop"
        val radius = extras.getInt("radius_m", 100)
        val heading = extras.getInt("heading", 0)
        val speed = readDouble(extras, "speed_kph", 10.0)
        val steps = readDouble(extras, "steps_per_sec", 2.0)
        val dur = extras.getInt("duration_min", 30)
        val lat = readDouble(extras, "lat", Double.NaN)
        val lon = readDouble(extras, "lon", Double.NaN)
        val sLat = if (lat.isNaN()) EngineService.curLat else lat
        val sLon = if (lon.isNaN()) EngineService.curLon else lon
        EngineService.startJog(this, sLat, sLon, mode, speed, steps, radius.toDouble(), heading.toDouble(), dur.toLong())
        finish()
    }

    private fun readDouble(extras: Bundle, key: String, dflt: Double): Double {
        if (!extras.containsKey(key)) return dflt
        val v: Any? = try { extras.get(key) } catch (t: Throwable) { null }
        return if (v is Number) v.toDouble() else dflt
    }

    private fun refreshStatus() {}

    private fun requestLocationPerms() {
        try {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ), 1)
        } catch (t: Throwable) {}
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            } catch (t: Throwable) {}
        }
    }

    private fun parse(s: String): Double =
        try { java.lang.Double.parseDouble(s.trim()) } catch (t: Throwable) { Double.NaN }

    private fun isValidCoord(lat: Double, lon: Double): Boolean =
        lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0

    private fun readCoord(i: Intent?, vararg keys: String): Double {
        if (i == null) return Double.NaN
        for (k in keys) {
            if (!i.hasExtra(k)) continue
            val v: Any? = try { i.extras?.get(k) } catch (t: Throwable) { null }
            if (v is Number) return v.toDouble()
            try { return i.getDoubleExtra(k, Double.NaN) } catch (t: Throwable) {}
            try { return i.getFloatExtra(k, Float.NaN).toDouble() } catch (t: Throwable) {}
        }
        return Double.NaN
    }

    private fun todayStart(): java.time.Instant =
        java.time.LocalDateTime.now().withHour(0).withMinute(0).withSecond(0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        poll.removeCallbacks(pollTask)
        super.onDestroy()
    }
}
