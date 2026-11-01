package com.pikminbot.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating pairing helper (v3.1).
 *
 * WHY AN OVERLAY: measured on the device — the system "Pair device with pairing
 * code" dialog starts a pairing server that is torn down the instant the dialog
 * stops being foreground (`/proc/net/tcp` shows LISTEN `0A` → gone within 3 s of
 * an app switch). A normal in-app dialog therefore can never complete a pairing:
 * by the time the user reads the code and returns to the app, the port is dead.
 *
 * The overlay solves it: the system dialog stays foreground (server alive) while
 * this small window floats on top, so the code can be typed without leaving it.
 *
 * Requires SYSTEM_ALERT_WINDOW (Settings → Special access → Display over other
 * apps; can also be granted with `adb shell appops set com.pikminbot.tools
 * SYSTEM_ALERT_WINDOW allow`).
 */
class PairOverlayService : Service() {

    companion object {
        private const val TAG = "PikminBotTools"
        const val ACTION_SHOW = "com.pikminbot.tools.SHOW_PAIR_OVERLAY"
        const val ACTION_HIDE = "com.pikminbot.tools.HIDE_PAIR_OVERLAY"

        fun start(ctx: Context) {
            val i = Intent(ctx, PairOverlayService::class.java).setAction(ACTION_SHOW)
            try { ctx.startService(i) } catch (t: Throwable) { Log.w(TAG, "overlay start failed", t) }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PairOverlayService::class.java)) } catch (t: Throwable) {}
        }
    }

    private var wm: WindowManager? = null
    private var view: View? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { removeOverlay(); stopSelf(); return START_NOT_STICKY }
        // Foreground: a plain background service is reaped as soon as the user
        // switches to Settings, which is exactly when the overlay must survive.
        goForeground()
        if (Settings.canDrawOverlays(this)) showOverlay() else stopSelf()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val chId = "pair_overlay"
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "ADB pairing", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, chId) else Notification.Builder(this))
                .setContentTitle("PikminBot Tools — ADB pairing")
                .setContentText("Type the system pairing code in the floating bar")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(11, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "overlay foreground start failed", t)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (view != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#F2202226"))
        }
        val title = TextView(this).apply {
            text = "Pair local ADB"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val hintTv = TextView(this).apply {
            text = "Open Developer options → Wireless debugging → Pair device with\n" +
                   "pairing code, then type that code here. Keep that dialog open."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
        }
        val portIn = EditText(this).apply {
            hint = "pairing port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            setSingleLine()
        }
        val codeIn = EditText(this).apply {
            hint = "6-digit code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            setSingleLine()
        }
        val status = TextView(this).apply {
            text = "port/code come from the system pairing dialog"
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 11f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pairBtn = Button(this).apply {
            text = "Pair"
            setOnClickListener {
                val port = portIn.text.toString().trim().toIntOrNull() ?: 0
                val code = codeIn.text.toString().trim()
                if (port <= 0 || code.length < 6) {
                    status.text = "need both the port and the 6-digit code"
                    return@setOnClickListener
                }
                status.text = "pairing…"
                Thread {
                    val pr = LocalAdb.pair(this@PairOverlayService, port, code)
                    val msg: String
                    if (pr.ok) {
                        val cp = LocalAdb.discover(this@PairOverlayService, 4000).connect ?: port
                        val cr = LocalAdb.connect(this@PairOverlayService, cp)
                        msg = if (cr.ok) "paired · connected on 127.0.0.1:$cp" else "paired, connect failed: ${cr.msg}"
                    } else {
                        msg = "pair failed: ${pr.msg.take(120)}"
                    }
                    main.post {
                        status.text = msg
                        if (msg.startsWith("paired")) {
                            main.postDelayed({ removeOverlay(); stopSelf() }, 2500)
                        }
                    }
                }.start()
            }
        }
        val closeBtn = Button(this).apply {
            text = "Close"
            setOnClickListener { removeOverlay(); stopSelf() }
        }
        row.addView(pairBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title)
        root.addView(hintTv)
        root.addView(portIn)
        root.addView(codeIn)
        root.addView(row)
        root.addView(status)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(80)
        }
        try {
            wm?.addView(root, lp)
            view = root
        } catch (t: Throwable) {
            Log.e(TAG, "overlay add failed", t)
            stopSelf()
        }
    }

    private fun removeOverlay() {
        view?.let { v ->
            try { wm?.removeView(v) } catch (t: Throwable) {}
        }
        view = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
