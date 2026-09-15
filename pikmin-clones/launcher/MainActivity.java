package com.peter.pikminclones;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Pikmin Clones launcher.
 *
 * Lists every launchable activity of Pikmin Bloom visible to this app across the
 * device's user profiles (personal user 0, Samsung clone/dual-app profile,
 * Secure Folder, ...) and starts the selected one with LauncherApps.
 *
 * Tapping a row brings that profile's Pikmin Bloom instance to the foreground,
 * so multiple accounts can stay signed in at once without adb.
 */
public class MainActivity extends Activity {

    private static final String TAG = "PikminClones";
    private static final String TARGET_PKG = "com.nianticlabs.pikmin";

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 24, 24, 24);
        sc.addView(list);
        setContentView(sc);
        populate();
    }

    private void header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18f);
        tv.setPadding(0, 32, 0, 12);
        list.addView(tv);
    }

    private void note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        list.addView(tv);
    }

    private void populate() {
        header("Pikmin Bloom instances on this device");
        UserManager um = (UserManager) getSystemService(USER_SERVICE);
        LauncherApps la = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        List<UserHandle> users = um.getUserProfiles();
        note("profiles visible: " + users.size());
        int found = 0;
        for (final UserHandle u : users) {
            List<LauncherActivityInfo> acts = la.getActivityList(TARGET_PKG, u);
            header("profile " + u.hashCode() + "  (" + acts.size() + " entries)");
            for (final LauncherActivityInfo ai : acts) {
                found++;
                Button btn = new Button(this);
                btn.setAllCaps(false);
                btn.setText("Open: " + ai.getLabel() + "  [user " + u.hashCode() + "]");
                btn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        launch(ai, u);
                    }
                });
                list.addView(btn, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        if (found == 0) {
            note("No Pikmin Bloom activity found. Install it in a profile first.");
        }
    }

    private void launch(LauncherActivityInfo ai, UserHandle u) {
        ComponentName cn = ai.getComponentName();
        LauncherApps la = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        try {
            la.startMainActivity(cn, u, null, null);
            Log.i(TAG, "started " + cn + " in user " + u.hashCode());
            Toast.makeText(this, "Opening " + cn.getPackageName() + " (user " + u.hashCode() + ")",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "start failed: " + t, t);
            Toast.makeText(this, "start failed: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
