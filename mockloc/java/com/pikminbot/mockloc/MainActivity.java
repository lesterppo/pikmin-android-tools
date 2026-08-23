package com.pikminbot.mockloc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Self-contained mock GPS injector with an embedded map.
 *
 *  - Tap the map to pick a point -> coords fill in and (if Inject is ON) inject.
 *  - Toggle "Inject" ON for persistent injection (MockService loops every ~1s
 *    so the fix never goes stale on Android 12+). OFF releases the mock.
 *  - No computer required.
 *
 * Map tiles come from OpenStreetMap via Leaflet (keyless, just INTERNET perm).
 */
public class MainActivity extends Activity {

    static final String EXTRA_AUTOSTART = "autostart";

    private EditText latIn, lonIn;
    private TextView status;
    private Switch toggle;
    private WebView map;
    private Handler poll = new Handler(Looper.getMainLooper());
    private Runnable pollTask = new Runnable() {
        @Override public void run() { refreshStatus(); poll.postDelayed(this, 500); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        latIn = findViewById(R.id.lat);
        lonIn = findViewById(R.id.lon);
        status = findViewById(R.id.status);
        toggle = findViewById(R.id.toggle);
        Button go = findViewById(R.id.go);
        map = findViewById(R.id.map);

        latIn.setText("22.3193");
        lonIn.setText("114.1694");

        go.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { applyFromFields(); }
        });

        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on) startFromFields();
                else MockService.stop(MainActivity.this);
            }
        });

        setupMap();
        requestLocationPerms();
        poll.postDelayed(pollTask, 500);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    // -------------------------------------------------------------- map
    private void setupMap() {
        WebSettings ws = map.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        map.setWebViewClient(new WebViewClient());
        map.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.d("MockMap", m.message());
                return true;
            }
        });
        map.addJavascriptInterface(new MapBridge(), "mockloc");

        String html = "<!DOCTYPE html><html><head><meta name='viewport' "
                + "content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<style>#map{position:absolute;top:0;bottom:0;left:0;right:0;}html,body{margin:0;height:100%;}</style>"
                + "</head><body><div id='map'></div><script>"
                // Esri World Street Map tiles: keyless, permitted for app use
                // (OpenStreetMap's volunteer servers block app-embedded traffic -> HTTP 418).
                + "var map=L.map('map',{zoomControl:true}).setView([22.3193,114.1694],15);"
                + "L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'&copy; Esri'}).addTo(map);"
                + "var marker=L.marker([22.3193,114.1694],{draggable:true}).addTo(map);"
                + "function emit(lat,lng){if(window.mockloc)mockloc.onPick(lat,lng);}"
                + "map.on('click',function(e){marker.setLatLng(e.latlng);emit(e.latlng.lat,e.latlng.lng);});"
                + "marker.on('dragend',function(e){var ll=e.target.getLatLng();emit(ll.lat,ll.lng);});"
                + "window.setMarker=function(lat,lng){marker.setLatLng([lat,lng]);map.setView([lat,lng],15);};"
                + "</script></body></html>";
        map.loadDataWithBaseURL("https://openstreetmap.org/", html, "text/html", "UTF-8", null);
    }

    private class MapBridge {
        @JavascriptInterface
        public void onPick(double lat, double lon) {
            final double la = lat, lo = lon;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    latIn.setText(String.format(Locale.US, "%.6f", la));
                    lonIn.setText(String.format(Locale.US, "%.6f", lo));
                    if (toggle.isChecked()) MockService.start(MainActivity.this, la, lo);
                    else refreshStatus();
                }
            });
        }
    }

    private void moveMapMarker(double lat, double lon) {
        map.evaluateJavascript("javascript:if(window.setMarker)setMarker("
                + lat + "," + lon + ");", null);
    }

    // -------------------------------------------------------------- logic
    private void applyFromFields() {
        double lat = parse(latIn.getText().toString());
        double lon = parse(lonIn.getText().toString());
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            Toast.makeText(this, "Enter valid latitude and longitude", Toast.LENGTH_SHORT).show();
            return;
        }
        moveMapMarker(lat, lon);
        if (toggle.isChecked()) MockService.start(this, lat, lon);
        else refreshStatus();
    }

    private void startFromFields() {
        double lat = parse(latIn.getText().toString());
        double lon = parse(lonIn.getText().toString());
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            toggle.setChecked(false);
            Toast.makeText(this, "Enter valid coordinates first", Toast.LENGTH_SHORT).show();
            return;
        }
        moveMapMarker(lat, lon);
        MockService.start(this, lat, lon);
    }

    private double parse(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return Double.NaN; }
    }

    private void refreshStatus() {
        if (MockService.running) {
            status.setText(String.format(Locale.US,
                    "INJECTING\nLat %.6f, Lon %.6f\nTicks %d  (last %d ms ago)",
                    MockService.curLat, MockService.curLon,
                    MockService.tickCount,
                    System.currentTimeMillis() - MockService.lastTick));
        } else {
            status.setText("Idle — not injecting.");
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        double lat = readCoord(intent, "lat", "latitude");
        double lon = readCoord(intent, "lon", "lng", "longitude");
        if (!Double.isNaN(lat)) { latIn.setText(String.format(Locale.US, "%.6f", lat)); moveMapMarker(lat, lon); }
        if (!Double.isNaN(lon)) lonIn.setText(String.format(Locale.US, "%.6f", lon));
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)
                && !Double.isNaN(lat) && !Double.isNaN(lon)) {
            toggle.setChecked(true);
            MockService.start(this, lat, lon);
        }
    }

    private static double readCoord(Intent i, String... keys) {
        for (String k : keys) {
            if (!i.hasExtra(k)) continue;
            try { return (double) i.getFloatExtra(k, Float.NaN); } catch (Throwable t) {}
            try { return i.getDoubleExtra(k, Double.NaN); } catch (Throwable t) {}
        }
        return Double.NaN;
    }

    private void requestLocationPerms() {
        try {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }, 1);
        } catch (Throwable t) {
            Toast.makeText(this, "Grant location permission in Settings", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        poll.removeCallbacks(pollTask);
        super.onDestroy();
    }
}
