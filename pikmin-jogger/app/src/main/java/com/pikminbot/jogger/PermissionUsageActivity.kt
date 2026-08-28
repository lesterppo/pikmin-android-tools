package com.pikminbot.jogger

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Required by Health Connect to READ records (VIEW_PERMISSION_USAGE intent). */
class PermissionUsageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing to render — existence of this activity satisfies the
        // VIEW_PERMISSION_USAGE / HEALTH_PERMISSIONS requirement.
        finish()
    }
}
