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
            try {
                if (::timeNow.isInitialized && timeContent.visibility == View.VISIBLE) refreshTimeStatus()
            } catch (t: Throwable) { /* tab not built yet */ }
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

    // ---------------------------- mock time tab fields -----------------------
    private lateinit var timeContent: LinearLayout
    private lateinit var timeNow: TextView
    private lateinit var timeTier: TextView
    private lateinit var timeTarget: TextView
    private lateinit var timeLog: TextView
    private lateinit var tzIn: EditText
    private lateinit var adbStatus: TextView
    private var targetEpochMs: Long = -1L

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
        val timeTabBtn = tabButton("Time")
        tabRow.addView(mockTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        tabRow.addView(jogTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        tabRow.addView(timeTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

        // Mock time tab content
        timeContent = buildTimePanel()
        timeContent.visibility = View.GONE
        root.addView(timeContent)

        // engine-level status + self-heal shared footer
        engineStatus = TextView(this).apply { text = "Engine: idle"; textSize = 12f; setPadding(0, dp(4), 0, 0) }
        root.addView(engineStatus)
        healNote = TextView(this).apply {
            setTextColor(Color.rgb(150, 60, 0)); textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { openRepair() }
        }
        root.addView(healNote)
        root.addView(Button(this).apply {
            text = "Mock location repair / status"
            setOnClickListener { openRepair() }
        })

        setContentView(root)

        mockTabBtn.setOnClickListener { selectTab("mockloc") }
        jogTabBtn.setOnClickListener { selectTab("jogger") }
        timeTabBtn.setOnClickListener { selectTab("time") }
        selectTab("mockloc")

        MockTime.init(this)
        SlotEvents.subscribe(slotListener)
        SlotWatch.start(this)
        try {
            registerReceiver(tzListener, android.content.IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        } catch (e: Throwable) {}
        requestLocationPerms()
        refreshJogStatus()  // init HC hint
        poll.postDelayed(pollTask, 400)
        handleIntent(intent)
    }

    private fun tabButton(name: String): Button = Button(this).apply { text = name }

    private fun selectTab(which: String) {
        mockContent.visibility = if (which == "mockloc") View.VISIBLE else View.GONE
        jogContent.visibility = if (which == "jogger") View.VISIBLE else View.GONE
        timeContent.visibility = if (which == "time") View.VISIBLE else View.GONE
        when (which) {
            "mockloc" -> refreshPinStatus()
            "jogger" -> refreshJogStatus()
            "time" -> refreshTimeStatus()
        }
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

        // Mushroom-safe path: fixed 90 s, then auto-release back to real GPS.
        // Use this when a game action refuses while an endless hold-pin runs.
        Button(this).apply {
            text = "Mushroom pin (90 s auto-release)"
            setOnClickListener { startMushroomPin() }
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
        warnIfDistant(lat, lon)
        if (injectToggle.isChecked) EngineService.startPin(this, lat, lon, persistent = true)
        Toast.makeText(this, String.format(Locale.US, "Pin %.6f, %.6f (inject %s)",
            lat, lon, if (injectToggle.isChecked) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
        refreshPinStatus()
    }

    /** Natural-lifetime pin: fixed 90 s, then hands the fix back to real GPS.
     *  Try this first when a game action refuses while the hold-pin is active. */
    private fun startMushroomPin() {
        val lat = parse(latIn.text.toString())
        val lon = parse(lonIn.text.toString())
        if (lat.isNaN() || lon.isNaN() || !isValidCoord(lat, lon)) {
            Toast.makeText(this, "Enter valid lat -90..90, lon -180..180", Toast.LENGTH_LONG).show()
            return
        }
        moveMapMarker(lat, lon)
        warnIfDistant(lat, lon)
        EngineService.startPin(this, lat, lon, persistent = false)
        Toast.makeText(this, String.format(Locale.US, "Mushroom pin 90 s @ %.6f, %.6f", lat, lon),
            Toast.LENGTH_LONG).show()
        refreshPinStatus()
    }

    /** Warn when the target is a long way from the real position: big jumps are
     *  flagged by the game's anti-cheat even when the mock itself is accepted. */
    private fun warnIfDistant(lat: Double, lon: Double) {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val real = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
                catch (t: Throwable) { null }
                ?: try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (t: Throwable) { null }
            if (real == null || real.isMock) return
            val res = FloatArray(1)
            android.location.Location.distanceBetween(real.latitude, real.longitude, lat, lon, res)
            val km = res[0] / 1000.0
            if (km > 2.0) {
                Toast.makeText(this, String.format(Locale.US,
                    "%.0f km from real position — large jumps get blocked", km),
                    Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {}
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

    // ============================================================ MOCK TIME TAB
    /**
     * Mock time / date / timezone panel.
     *
     * Clock and timezone injection are privileged (SET_TIME / SET_TIME_ZONE are
     * signature permissions), so this tab drives MockTime, which executes the
     * same `cmd alarm set-time` / `cmd alarm set-timezone` the shell uid uses,
     * through root or Shizuku. The tab states plainly which tier is live.
     */
    private fun buildTimePanel(): LinearLayout {
        val parent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeNow = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(6), 0, dp(2))
        }
        parent.addView(timeNow)
        timeTier = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        }
        parent.addView(timeTier)

        // --- target date / time ------------------------------------------------
        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(Button(this).apply {
            text = "Set date"
            setOnClickListener { pickDate() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        pickRow.addView(Button(this).apply {
            text = "Set time"
            setOnClickListener { pickTime() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        pickRow.addView(Button(this).apply {
            text = "Now"
            setOnClickListener {
                targetEpochMs = -1L
                refreshTimeStatus()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(pickRow)

        timeTarget = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }
        parent.addView(timeTarget)

        // --- timezone ----------------------------------------------------------
        val tzRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tzIn = EditText(this).apply {
            hint = "Timezone (Olson id, e.g. Asia/Tokyo)"
            setText("Asia/Tokyo")
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
        }
        tzRow.addView(tzIn)
        tzRow.addView(Button(this).apply {
            text = "TZ only"
            setOnClickListener {
                val tz = tzIn.text.toString().trim()
                runTimeOp("timezone") { MockTime.applyMock(this@MainActivity, null, tz) }
            }
        })
        parent.addView(tzRow)

        // quick zone chips
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, label) in MockTime.zoneChoices().take(4)) {
            chipRow.addView(Button(this).apply {
                text = label.substringBefore(" ")
                textSize = 11f
                setOnClickListener { tzIn.setText(id) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(2) })
        }
        parent.addView(chipRow)

        // --- relative shifts ---------------------------------------------------
        val shiftRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, hours) in listOf("-1d" to -24.0, "-1h" to -1.0, "+1h" to 1.0, "+1d" to 24.0)) {
            shiftRow.addView(Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { runTimeOp("shift $label") { MockTime.shift(this@MainActivity, hours) } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(2) })
        }
        parent.addView(shiftRow)

        // --- apply / restore ---------------------------------------------------
        parent.addView(Button(this).apply {
            text = "Apply mock time + timezone"
            setOnClickListener {
                val tz = tzIn.text.toString().trim().takeIf { it.isNotBlank() }
                val target = if (targetEpochMs > 0) targetEpochMs else null
                if (target == null && tz == null) {
                    appendTimeLog("Pick a date/time or a timezone first")
                    return@setOnClickListener
                }
                runTimeOp("apply") { MockTime.applyMock(this@MainActivity, target, tz) }
            }
        })
        parent.addView(Button(this).apply {
            text = "Grant Shizuku"
            setOnClickListener {
                MockTime.resetRootCache()
                if (!MockTime.shizukuBinderAlive()) {
                    appendTimeLog(
                        "Shizuku is not running. Open the Shizuku app and start it " +
                        "(wireless-debugging start works without a computer on Android 11+), " +
                        "then tap this again."
                    )
                } else {
                    MockTime.requestShizukuPermission()
                    appendTimeLog("Asked Shizuku for permission — approve the dialog.")
                }
                refreshTimeStatus()
            }
        })
        // --- local ADB tier -----------------------------------------------------
        adbStatus = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
        }
        parent.addView(adbStatus)
        val adbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adbRow.addView(Button(this).apply {
            text = "Pair ADB"
            textSize = 12f
            setOnClickListener { startPairing() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        adbRow.addView(Button(this).apply {
            text = "Reconnect"
            textSize = 12f
            setOnClickListener { reconnectAdb() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        adbRow.addView(Button(this).apply {
            text = "Test ADB"
            textSize = 12f
            setOnClickListener {
                runTimeOp("adb test") {
                    val sh = LocalAdb.shell(this@MainActivity, "id")
                    "ok=${sh.ok} ${sh.msg}"
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(adbRow)
        parent.addView(Button(this).apply {
            text = "Import adb key from file"
            textSize = 12f
            setOnClickListener { importKeyDialog() }
        })

        parent.addView(Button(this).apply {
            text = "Restore real time"
            setOnClickListener { runTimeOp("restore") { MockTime.restore(this@MainActivity) } }
        })

        timeLog = TextView(this).apply {
            textSize = 11f
            setPadding(0, dp(6), 0, dp(6))
        }
        parent.addView(timeLog)

        refreshTimeStatus()
        return parent
    }

    /**
     * Connect the local-ADB transport. mDNS usually reveals the wireless-debug
     * port, but on some builds NsdManager returns nothing (VPN on, or the
     * service is filtered), so fall back to asking for the port that the
     * Wireless debugging screen shows.
     */
    private fun reconnectAdb() {
        appendTimeLog("adb: discovering connect port…")
        Thread {
            val detected = LocalAdb.detectConnectPort(this)
            runOnUiThread {
                if (detected > 0) {
                    runTimeOp("adb connect") {
                        val r = LocalAdb.connect(this, detected)
                        "port $detected ok=${r.ok} ${r.msg}"
                    }
                } else {
                    val et = EditText(this).apply {
                        hint = "port from Wireless debugging (e.g. 44133)"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText(LocalAdb.connectPort(this@MainActivity).takeIf { it > 0 }?.toString() ?: "")
                    }
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                    }
                    box.addView(TextView(this).apply {
                        textSize = 12f
                        text = "mDNS found no adb port. Read it from Developer options → " +
                                "Wireless debugging → IP address and port."
                    })
                    box.addView(et)
                    android.app.AlertDialog.Builder(this)
                        .setTitle("ADB connect port")
                        .setView(box)
                        .setPositiveButton("Connect") { _, _ ->
                            val p = et.text.toString().trim().toIntOrNull() ?: 0
                            runTimeOp("adb connect") {
                                if (p <= 0) return@runTimeOp "invalid port"
                                LocalAdb.setConnectPort(this, p)
                                val r = LocalAdb.connect(this, p)
                                "port $p ok=${r.ok} ${r.msg}"
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    /**
     * Seed a trusted adb key: push adbkey + adbkey.pub into
     * /sdcard/Android/data/<pkg>/files/ then tap this. Needed on OEM builds that
     * refuse to draw our overlay over Settings (the pairing bar then cannot be
     * used, and the pairing server dies when the dialog is backgrounded).
     */
    private fun importKeyDialog() {
        val dir = getExternalFilesDir(null)
        val key = java.io.File(dir, "adbkey")
        val pub = java.io.File(dir, "adbkey.pub")
        if (!key.exists() || !pub.exists()) {
            appendTimeLog("Put adbkey + adbkey.pub in:\n${dir?.absolutePath}")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Import adb key?")
            .setMessage("This app will use the supplied adb private key to open a shell " +
                    "channel to this device. Only import a key you own.")
            .setPositiveButton("Import") { _, _ ->
                runTimeOp("import key") {
                    val r = LocalAdb.importKey(this, key, pub)
                    val c = LocalAdb.detectConnectPort(this)
                    val cr = LocalAdb.connect(this, c)
                    "import ok=${r.ok} ${r.msg} · connect:$c ok=${cr.ok} ${cr.msg}"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Pairing has to happen in a floating overlay: the system pairing dialog
     * stops advertising its port the moment it loses foreground (measured), so a
     * normal dialog in this app can never catch the code in time.
     */
    private fun startPairing() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            appendTimeLog("Grant \"Display over other apps\" for PikminBot Tools, then tap Pair ADB again.")
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                appendTimeLog("cannot open overlay settings: ${t.message}")
            }
            return
        }
        PairOverlayService.start(this)
        appendTimeLog("Floating pair bar opened — open the system pairing dialog, type the code there.")
    }

    /**
     * Pair this app's own adb key with the phone's wireless-debugging service.
     * Ports come from mDNS; the pairing port only exists while the system
     * "Pair device with pairing code" dialog is open, so the user opens it,
     * reads the 6-digit code and types it here.
     */
    private fun pairAdbDialog() {
        appendTimeLog("adb: discovering ports (open the pairing dialog first)…")
        Thread {
            val ports = LocalAdb.discover(this, 5000)
            runOnUiThread {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                box.addView(TextView(this).apply {
                    textSize = 12f
                    text = "mDNS: pairing port ${ports.pairing ?: "not advertised"}, " +
                            "connect port ${ports.connect ?: "not advertised"}"
                })
                val portIn = EditText(this).apply {
                    hint = "Pairing port"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText((ports.pairing ?: 0).toString().takeIf { (ports.pairing ?: 0) > 0 } ?: "")
                }
                val codeIn = EditText(this).apply {
                    hint = "6-digit pairing code"
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
                box.addView(portIn)
                box.addView(codeIn)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Pair local ADB")
                    .setMessage("Developer options → Wireless debugging → Pair device with pairing code")
                    .setView(box)
                    .setPositiveButton("Pair") { _, _ ->
                        val pp = portIn.text.toString().trim().toIntOrNull() ?: 0
                        val code = codeIn.text.toString().trim()
                        runTimeOp("adb pair") {
                            if (pp <= 0) return@runTimeOp "no pairing port — open the pairing dialog"
                            if (code.length < 6) return@runTimeOp "pairing code must be 6 digits"
                            val pr = LocalAdb.pair(this, pp, code)
                            if (!pr.ok) return@runTimeOp "pair failed: ${pr.msg}"
                            val cp = LocalAdb.discover(this, 4000).connect ?: pp
                            val cr = LocalAdb.connect(this, cp)
                            "paired on :$pp, connect :$cp -> ok=${cr.ok} ${cr.msg}"
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun pickDate() {
        val base = java.util.Calendar.getInstance()
        if (targetEpochMs > 0) base.timeInMillis = targetEpochMs
        android.app.DatePickerDialog(
            this, { _, y, m, d ->
                val c = java.util.Calendar.getInstance()
                if (targetEpochMs > 0) c.timeInMillis = targetEpochMs
                c.set(java.util.Calendar.YEAR, y)
                c.set(java.util.Calendar.MONTH, m)
                c.set(java.util.Calendar.DAY_OF_MONTH, d)
                targetEpochMs = c.timeInMillis
                refreshTimeStatus()
            },
            base.get(java.util.Calendar.YEAR),
            base.get(java.util.Calendar.MONTH),
            base.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime() {
        val base = java.util.Calendar.getInstance()
        if (targetEpochMs > 0) base.timeInMillis = targetEpochMs
        android.app.TimePickerDialog(
            this, { _, h, min ->
                val c = java.util.Calendar.getInstance()
                if (targetEpochMs > 0) c.timeInMillis = targetEpochMs
                c.set(java.util.Calendar.HOUR_OF_DAY, h)
                c.set(java.util.Calendar.MINUTE, min)
                c.set(java.util.Calendar.SECOND, 0)
                targetEpochMs = c.timeInMillis
                refreshTimeStatus()
            },
            base.get(java.util.Calendar.HOUR_OF_DAY),
            base.get(java.util.Calendar.MINUTE),
            true
        ).show()
    }

    /** Run a privileged time op off the UI thread and show what actually happened. */
    private fun runTimeOp(label: String, op: () -> String) {
        appendTimeLog("$label …")
        Thread {
            val out = try { op() } catch (t: Throwable) { "ERROR ${t.javaClass.simpleName}: ${t.message}" }
            runOnUiThread {
                appendTimeLog("$label -> $out")
                refreshTimeStatus()
            }
        }.start()
    }

    private fun refreshTimeStatus() {
        if (!::timeNow.isInitialized) return
        val s = MockTime.snapshot(this)
        timeNow.text = "Device clock: ${s.text}"
        val tier = MockTime.tierName()
        val missing = MockTime.missingTier()
        timeTier.text = if (missing == null) {
            "Injection tier: $tier" + if (MockTime.isActive(this)) " · MOCK ACTIVE" else ""
        } else {
            "Injection tier: $tier — $missing"
        }
        if (::adbStatus.isInitialized) {
            val avail = LocalAdb.available(this)
            val paired = LocalAdb.isPaired(this)
            val cp = LocalAdb.connectPort(this)
            adbStatus.text = when {
                !avail -> "Local ADB: binary not bundled"
                !paired -> "Local ADB: bundled, not paired — tap Pair ADB once"
                cp > 0 -> "Local ADB: paired · 127.0.0.1:$cp"
                else -> "Local ADB: paired, no port yet — tap Reconnect"
            }
        }
        if (::timeTarget.isInitialized) {
            timeTarget.text = if (targetEpochMs > 0) {
                "Target: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(java.util.Date(targetEpochMs)) + "  (device local zone)"
            } else "Target: clock unchanged (now)"
        }
    }

    private fun appendTimeLog(s: String) {
        if (!::timeLog.isInitialized) return
        val cur = timeLog.text.toString()
        timeLog.text = if (cur.isBlank()) s else cur + "\n" + s
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
        val slotOk = SelfHeal.slotOk(this)
        if (slotOk) {
            healNote.setTextColor(Color.rgb(0, 120, 0))
            healNote.text = "Mock location slot: OK (appop allow) · tap for repair options"
        } else {
            healNote.setTextColor(Color.rgb(180, 0, 0))
            healNote.text = "Mock location slot LOST — injection will be refused.\n" +
                "Tap here to repair (${SelfHeal.describe(this)})"
        }
        if (SelfHeal.lastSecurityExceptionMs > 0L && slotOk) {
            val ago = ((System.currentTimeMillis() - SelfHeal.lastSecurityExceptionMs) / 1000).coerceAtLeast(0)
            val note = " · recovered ${ago}s ago via ${SelfHeal.lastRepair.ifBlank { "?" }}"
            healNote.text = healNote.text.toString() + note
        }
        if (EngineService.running && EngineService.mode == "jog") refreshJogStatus()
        else if (EngineService.running && EngineService.mode == "pin") refreshPinStatus()
    }

    /** The platform broadcasts a zone change; java.util.TimeZone caches the old
     *  value in-process, so reset it here or the clock line lies. */
    private val tzListener = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            try { java.util.TimeZone.setDefault(null) } catch (e: Throwable) {}
            runOnUiThread { try { refreshTimeStatus() } catch (e: Throwable) {} }
        }
    }

    private fun openRepair() {
        try {
            startActivity(Intent(this, RepairActivity::class.java))
        } catch (t: Throwable) {
            Log.e("PikminBotTools", "open repair failed", t)
        }
    }

    /** SlotWatch events (developer-mode toggle, selection change) refresh the banner. */
    private val slotListener: (String) -> Unit = { ev ->
        runOnUiThread { refreshEngineStatus() }
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

    override fun onResume() {
        super.onResume()
        try { MockTime.syncDefaultZone(); refreshTimeStatus() } catch (e: Throwable) {}
        // Coming back from Developer options: re-probe and repair immediately so
        // a pin can be re-armed without the user thinking about it.
        poll.postDelayed({
            try {
                if (!SelfHeal.slotOk(this)) {
                    SelfHeal.attemptRepair(this, "MainActivity.resume")
                    if (EngineService.running && !SelfHeal.slotOk(this)) EngineService.degraded = true
                } else if (EngineService.degraded) {
                    EngineService.degraded = false
                    EngineService.rearm(this)
                }
            } catch (t: Throwable) {}
            refreshEngineStatus()
        }, 600)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        poll.removeCallbacks(pollTask)
        try { unregisterReceiver(tzListener) } catch (e: Throwable) {}
        SlotEvents.unsubscribe(slotListener)
        SlotWatch.stop()
        super.onDestroy()
    }
}
