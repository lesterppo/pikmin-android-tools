package com.pikminbot.hcsteps

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Required by Health Connect for apps that READ health data: users must be
 * able to reach a "how your data is used" screen from the Health Connect app.
 */
class PermissionUsageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "HC Step Injector: data stays on-device in Health Connect.", Toast.LENGTH_LONG).show()
        finish()
    }
}
