package com.pikminbot.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pikmin Updater - one-click updater for the re-signed Pikmin Bloom clones
 * (com.pikmin.c1 / c2 / c3).
 *
 * APK sources:
 *   - the app's own external files dir: /sdcard/Android/data/com.pikminbot.updater/files/apks/
 *   - /sdcard/PikminUpdates/  (when readable)
 *   - the system file picker ("Add APK")
 *   - an optional HTTP base URL ("From PC") serving pikmin-manifest.json
 *
 * Install paths:
 *   - Shizuku (shell, silent, no taps) when Shizuku is running and this app is
 *     authorised
 *   - PackageInstaller session otherwise (one system confirm tap per app)
 */
public class MainActivity extends Activity {

    static final String[] TARGETS = {"com.pikmin.c1", "com.pikmin.c2", "com.pikmin.c3"};
    static final String[] LABELS  = {"Pikmin Bloom C1", "Pikmin Bloom C2", "Pikmin Bloom C3"};

    static final String ACTION_INSTALL_RESULT = "com.pikminbot.updater.INSTALL_RESULT";
    static final int REQ_PICK = 1001;

    final Handler ui = new Handler(Looper.getMainLooper());

    LinearLayout listBox;
    TextView statusLine;
    TextView shizukuLine;
    TextView dirLine;
    TextView serverLine;
    Button updateAllBtn;
    Button shizukuBtn;

    final Map<String, Apk> apks = new HashMap<String, Apk>();
    int dbgFiles = 0, dbgParsed = 0, dbgMatched = 0;
    String dbgErr = "";
    String dbgDirs = "";
    final Map<String, TextView> rowStatus = new HashMap<String, TextView>();
    final Map<String, TextView> rowTitle = new HashMap<String, TextView>();

    final List<String> queue = new ArrayList<String>();
    int queueIdx = 0;
    int okCount = 0;
    int failCount = 0;
    StringBuilder failures = new StringBuilder();

    SharedPreferences prefs;

    // ---------------------------------------------------------------- model

    static class Apk {
        String pkg;
        String path;
        long versionCode;
        String versionName;
        Apk(String pkg, String path, long vc, String vn) {
            this.pkg = pkg; this.path = path; this.versionCode = vc; this.versionName = vn;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("updater", MODE_PRIVATE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter(ACTION_INSTALL_RESULT);
        registerReceiver(installReceiver, f, RECEIVER_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuLine();
        scan();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installReceiver); } catch (Throwable t) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Pikmin Updater");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.parseColor("#1b3a2b"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Updates the re-signed clones in one tap. Keeps game accounts and settings.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(Color.parseColor("#555555"));
        root.addView(sub);

        shizukuLine = new TextView(this);
        shizukuLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        shizukuLine.setPadding(0, dp(10), 0, 0);
        root.addView(shizukuLine);

        shizukuBtn = new Button(this);
        shizukuBtn.setVisibility(View.GONE);
        root.addView(shizukuBtn);

        dirLine = new TextView(this);
        dirLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dirLine.setTextColor(Color.parseColor("#666666"));
        dirLine.setPadding(0, dp(12), 0, dp(4));
        root.addView(dirLine);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        updateAllBtn = new Button(this);
        updateAllBtn.setText("Update all");
        updateAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        updateAllBtn.setLayoutParams(lp);
        updateAllBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startUpdateAll(); }
        });
        root.addView(updateAllBtn);

        statusLine = new TextView(this);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusLine.setPadding(0, dp(12), 0, dp(4));
        statusLine.setTextColor(Color.parseColor("#222222"));
        root.addView(statusLine);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        Button addApk = new Button(this);
        addApk.setText("Add APK");
        addApk.setLayoutParams(tlp);
        addApk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickApks(); }
        });
        tools.addView(addApk);

        Button fromPc = new Button(this);
        fromPc.setText("From PC");
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fromPc.setLayoutParams(tlp2);
        fromPc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fetchFromPc(); }
        });
        tools.addView(fromPc);

        Button rescan = new Button(this);
        rescan.setText("Rescan");
        LinearLayout.LayoutParams tlp3 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rescan.setLayoutParams(tlp3);
        rescan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scan(); refreshShizukuLine(); }
        });
        tools.addView(rescan);

        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolsLp.topMargin = dp(14);
        tools.setLayoutParams(toolsLp);
        root.addView(tools);

        serverLine = new TextView(this);
        serverLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        serverLine.setTextColor(Color.parseColor("#666666"));
        serverLine.setPadding(0, dp(6), 0, 0);
        serverLine.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editServerUrl(); }
        });
        serverLine.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editServerUrl(); }
        });
        root.addView(serverLine);

        TextView hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setPadding(0, dp(14), 0, 0);
        hint.setText("Put c1.apk / c2.apk / c3.apk in the folder above (adb push) "
                + "or tap Add APK. Tap the server line to change the PC address.\n"
                + "Signature identity is preserved, so accounts and save data survive the update.");
        root.addView(hint);

        return scroll;
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    void refreshShizukuLine() {
        shizukuLine.setText("Install mode: system installer - one confirm tap per app");
        shizukuLine.setTextColor(Color.parseColor("#666666"));
        shizukuBtn.setVisibility(View.GONE);
        serverLine.setText("PC address: " + prefs.getString("server", defaultServer())
                + "  (tap to change)");
    }

    String defaultServer() {
        return "http://192.168.0.210:8942/";
    }

    // ---------------------------------------------------------------- scanning

    /**
     * Preferred APK folder. The external files dir is the friendly one (adb push
     * target), but if a shell/adb process created it first it is owned by
     * "shell" and the app cannot even list it - listFiles() returns null. In that
     * case fall back to internal storage so the app still functions.
     */
    File apkDir() {
        File ext = null;
        File base = getExternalFilesDir(null);
        if (base != null) {
            File d = new File(base, "apks");
            if (!d.exists()) d.mkdirs();
            if (d.listFiles() != null) ext = d;
        }
        if (ext != null) return ext;
        File in = new File(getFilesDir(), "apks");
        if (!in.exists()) in.mkdirs();
        return in;
    }

    List<File> candidateDirs() {
        List<File> dirs = new ArrayList<File>();
        File base = getExternalFilesDir(null);
        if (base != null) dirs.add(new File(base, "apks"));
        dirs.add(new File(getFilesDir(), "apks"));
        dirs.add(new File("/sdcard/PikminUpdates"));
        dirs.add(new File("/sdcard/Download/Android/data/com.pikminbot.updater/files/apks"));
        return dirs;
    }

    void scan() {
        apks.clear();
        dbgFiles = 0; dbgParsed = 0; dbgMatched = 0; dbgErr = ""; dbgDirs = "";
        List<File> files = new ArrayList<File>();
        List<File> dirs = candidateDirs();
        for (int i = 0; i < dirs.size(); i++) {
            if (dbgDirs.length() == 0) dbgDirs = dirs.get(i).getAbsolutePath();
            collect(dirs.get(i), files, 2);
        }

        PackageManager pm = getPackageManager();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            dbgFiles++;
            PackageInfo pi = null;
            try {
                pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            } catch (Throwable t) {
                dbgErr = f.getName() + ": " + t;
            }
            if (pi == null) { if (dbgErr.length() == 0) dbgErr = "null for " + f.getName(); continue; }
            dbgParsed++;
            boolean wanted = false;
            for (int k = 0; k < TARGETS.length; k++) if (TARGETS[k].equals(pi.packageName)) wanted = true;
            if (!wanted) continue;
            dbgMatched++;
            Apk cur = apks.get(pi.packageName);
            long vc = (int) pi.versionCode;
            try { vc = PackageInfoCompatVersion(pi); } catch (Throwable t) { }
            if (cur == null || vc > cur.versionCode) {
                apks.put(pi.packageName, new Apk(pi.packageName, f.getAbsolutePath(), vc,
                        pi.versionName == null ? "?" : pi.versionName));
            }
        }
        renderRows();
    }

    static long PackageInfoCompatVersion(PackageInfo pi) {
        try {
            return pi.getLongVersionCode();
        } catch (Throwable t) {
            return pi.versionCode;
        }
    }

    void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth < 0) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            if (f.isDirectory()) collect(f, out, depth - 1);
            else if (f.getName().toLowerCase().endsWith(".apk")) out.add(f);
        }
    }

    String installedVersion(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    long installedCode(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return PackageInfoCompatVersion(pi);
        } catch (Throwable t) {
            return -1;
        }
    }

    void renderRows() {
        listBox.removeAllViews();
        rowStatus.clear();
        rowTitle.clear();
        int pending = 0;
        for (int i = 0; i < TARGETS.length; i++) {
            final String pkg = TARGETS[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);
            row.setLayoutParams(rlp);
            row.setBackgroundColor(Color.parseColor("#f2f5f3"));

            TextView t = new TextView(this);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(Color.parseColor("#11221a"));
            t.setText(LABELS[i]);
            row.addView(t);

            TextView st = new TextView(this);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

            String inst = installedVersion(pkg);
            Apk a = apks.get(pkg);
            if (inst == null) {
                st.setText("not installed" + (a == null ? "" : "  •  apk " + a.versionName + " ready"));
                st.setTextColor(Color.parseColor("#666666"));
            } else if (a == null) {
                st.setText("installed " + inst + "  •  no APK found");
                st.setTextColor(Color.parseColor("#b06a00"));
            } else if (a.versionCode > installedCode(pkg)) {
                st.setText("installed " + inst + "  →  " + a.versionName + "  (update ready)");
                st.setTextColor(Color.parseColor("#1b5ecb"));
                pending++;
            } else if (a.versionCode == installedCode(pkg)) {
                st.setText("installed " + inst + "  •  up to date");
                st.setTextColor(Color.parseColor("#1b7a3f"));
            } else {
                st.setText("installed " + inst + "  •  APK is older (" + a.versionName + ")");
                st.setTextColor(Color.parseColor("#666666"));
            }
            row.addView(st);

            rowStatus.put(pkg, st);
            rowTitle.put(pkg, t);
            listBox.addView(row);
        }
        updateAllBtn.setText(pending == 0
                ? "Update all (nothing ready)"
                : "Update all (" + pending + ")");
        updateAllBtn.setEnabled(pending > 0 || hasAnyApk());
        String dbg = "";
        if (dbgFiles == 0) {
            dbg = "\nNo APK files visible. Launch this app once BEFORE pushing files with adb"
                + " (an adb-created folder is not readable by the app), or use Add APK / From PC."
                + (dbgErr.length() > 0 ? "\n" + dbgErr : "");
        }
        dirLine.setText("APK folder: " + apkDir().getAbsolutePath() + dbg);
    }

    boolean hasAnyApk() {
        return !apks.isEmpty();
    }

    // ---------------------------------------------------------------- update all

    void startUpdateAll() {
        if (apks.isEmpty()) {
            toast("No APKs found. Put c1/c2/c3.apk in " + apkDir().getAbsolutePath());
            return;
        }
        listForQueue();
        if (queue.isEmpty()) {
            toast("Everything is already up to date.");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("Allow 'Install unknown apps' for Pikmin Updater");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) { }
            return;
        }
        okCount = 0; failCount = 0; failures.setLength(0);
        queueIdx = 0;
        statusLine.setText("Installing " + queue.size() + " app(s) - confirm each system dialog");
        installNextSession();
    }

    void listForQueue() {
        queue.clear();
        for (int i = 0; i < TARGETS.length; i++) {
            String pkg = TARGETS[i];
            Apk a = apks.get(pkg);
            if (a == null) continue;
            long inst = installedCode(pkg);
            if (a.versionCode > inst) queue.add(pkg);
        }
    }

    // ---- helpers

    String brief(String s) {
        if (s == null) return "no output";
        s = s.trim();
        if (s.length() > 160) s = s.substring(0, 160) + "…";
        return s.replace("\n", " | ");
    }

    // ---- PackageInstaller path

    void installNextSession() {
        if (queueIdx >= queue.size()) { afterQueue(); return; }
        final String pkg = queue.get(queueIdx);
        final Apk a = apks.get(pkg);
        if (a == null) { queueIdx++; installNextSession(); return; }
        rowStatus.get(pkg).setText("installing " + a.versionName + "…");

        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setAppPackageName(pkg);
                    int sid = pi.createSession(p);
                    PackageInstaller.Session s = pi.openSession(sid);
                    File f = new File(a.path);
                    InputStream in = new FileInputStream(f);
                    OutputStream out = s.openWrite("base.apk", 0, f.length());
                    try {
                        byte[] buf = new byte[1 << 20];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.flush();
                        try { s.fsync(out); } catch (Throwable t) { }
                    } finally {
                        try { in.close(); } catch (Throwable t) { }
                        try { out.close(); } catch (Throwable t) { }
                    }
                    s.close();
                    s.commit(pendingIntentFor(sid).getIntentSender());
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                if (err != null) {
                    final String e = err;
                    ui.post(new Runnable() { public void run() {
                        failCount++; failures.append(pkg).append(": ").append(brief(e)).append("\n");
                        queueIdx++;
                        installNextSession();
                    }});
                }
            }
        }).start();
    }

    PendingIntent pendingIntentFor(int sid) {
        Intent i = new Intent(ACTION_INSTALL_RESULT);
        i.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(this, sid, i, flags);
    }

    final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
            if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String pkg = queueIdx < queue.size() ? queue.get(queueIdx) : "?";
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(confirm); } catch (Throwable t) { }
                }
                if (rowStatus.get(pkg) != null)
                    rowStatus.get(pkg).setText("confirm the system dialog…");
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                okCount++;
                if (rowStatus.get(pkg) != null)
                    rowStatus.get(pkg).setText("updated ✓");
            } else {
                failCount++;
                failures.append(pkg).append(": ").append(brief(msg)).append("\n");
                if (rowStatus.get(pkg) != null)
                    rowStatus.get(pkg).setText("failed: " + brief(msg));
            }
            queueIdx++;
            installNextSession();
        }
    };

    void afterQueue() {
        scan();
        String s = "Done — " + okCount + " updated";
        if (failCount > 0) s += ", " + failCount + " failed:\n" + failures.toString().trim();
        statusLine.setText(s);
        updateAllBtn.setText("Update all");
        if (okCount > 0) toast(okCount + " app(s) updated");
    }

    // ---------------------------------------------------------------- sources

    void pickApks() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); }
        catch (Throwable t) { toast("No file picker available"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            copyUris(uris);
        }
    }

    void copyUris(final List<Uri> uris) {
        statusLine.setText("Copying " + uris.size() + " file(s)…");
        new Thread(new Runnable() {
            public void run() {
                int ok = 0;
                for (int i = 0; i < uris.size(); i++) {
                    try {
                        InputStream in = getContentResolver().openInputStream(uris.get(i));
                        File dst = new File(apkDir(), "picked-" + System.currentTimeMillis() + "-" + i + ".apk");
                        FileOutputStream out = new FileOutputStream(dst);
                        byte[] buf = new byte[1 << 20];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close(); out.close();
                        ok++;
                    } catch (Throwable t) { }
                }
                final int fok = ok;
                ui.post(new Runnable() { public void run() {
                    statusLine.setText("Copied " + fok + " APK(s)");
                    scan();
                }});
            }
        }).start();
    }

    void editServerUrl() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(prefs.getString("server", defaultServer()));
        new AlertDialog.Builder(this)
                .setTitle("PC address")
                .setMessage("Base URL serving pikmin-manifest.json (e.g. http://192.168.0.210:8942/)")
                .setView(e)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().putString("server", e.getText().toString().trim()).apply();
                        refreshShizukuLine();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void fetchFromPc() {
        final String base = prefs.getString("server", defaultServer());
        statusLine.setText("Fetching manifest from " + base + "…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int got = 0;
                try {
                    String mUrl = base.endsWith("/") ? base + "pikmin-manifest.json" : base + "/pikmin-manifest.json";
                    String json = httpGet(mUrl);
                    JSONObject o = new JSONObject(json);
                    List<String> names = new ArrayList<String>();
                    if (o.has("files")) {
                        Object fl = o.get("files");
                        if (fl instanceof JSONObject) {
                            JSONObject fo = (JSONObject) fl;
                            java.util.Iterator<String> it = fo.keys();
                            while (it.hasNext()) names.add(fo.getString(it.next()));
                        } else if (fl instanceof org.json.JSONArray) {
                            org.json.JSONArray arr = (org.json.JSONArray) fl;
                            for (int i = 0; i < arr.length(); i++) names.add(arr.getString(i));
                        }
                    }
                    for (int i = 0; i < names.size(); i++) {
                        String name = names.get(i);
                        String u = base.endsWith("/") ? base + name : base + "/" + name;
                        File dst = new File(apkDir(), name);
                        download(u, dst);
                        got++;
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final int fgot = got;
                final String ferr = err;
                ui.post(new Runnable() { public void run() {
                    if (ferr == null) statusLine.setText("Fetched " + fgot + " APK(s) from PC");
                    else statusLine.setText("Fetch failed: " + brief(ferr));
                    scan();
                }});
            }
        }).start();
    }

    String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try {
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    void download(String url, File dst) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        try {
            InputStream in = c.getInputStream();
            File tmp = new File(dst.getAbsolutePath() + ".part");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        } finally {
            c.disconnect();
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
