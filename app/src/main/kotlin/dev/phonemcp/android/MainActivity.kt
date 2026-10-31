package dev.phonemcp.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.Window
import android.view.MotionEvent
import android.widget.*

/** Session authorization is volatile: process death/reboot never restores control. */
object PhoneBridge {
    @Volatile var accessibility: PhoneAccessibilityService? = null
    @Volatile var active = false
    @Volatile var scopes: Set<String> = emptySet()
    @Volatile var token: String? = null
    @Volatile var statusMessage = "Stopped"
    @Volatile var pendingActivation: String? = null
        private set

    /** A one-use local consent ticket; queued START intents cannot outlive Stop. */
    fun requestActivation(scopes: Set<String>): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!active && pendingActivation == null) { "A session is active or starting" }
        require(scopes.all { it == "screen.read" || it == "screen.act" })
        val activation = java.util.UUID.randomUUID().toString()
        this.scopes = scopes.toSet()
        pendingActivation = activation
        return activation
    }

    fun consumeActivation(id: String?): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active || id == null || pendingActivation != id) return false
        pendingActivation = null
        return true
    }

    /** Called only from UI/service main-thread lifecycle callbacks. */
    fun revokeSession() {
        check(Looper.myLooper() == Looper.getMainLooper())
        pendingActivation = null
        active = false
        token = null
        scopes = emptySet()
        accessibility?.revokeSnapshot()
    }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var token: TextView
    private lateinit var read: CheckBox
    private lateinit var act: CheckBox
    private lateinit var start: Button
    private var dialog: AlertDialog? = null
    private var startRequested = false
    private var permissionRequested = false
    private val activityTouchGuard = TouchGuard()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protectWindow(window)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding((20 * density).toInt() + insets.systemWindowInsetLeft, (24 * density).toInt() + insets.systemWindowInsetTop, (20 * density).toInt() + insets.systemWindowInsetRight, (24 * density).toInt() + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value; textSize = size
            val density = resources.displayMetrics.density
            setPadding(0, (8 * density).toInt(), 0, (14 * density).toInt()); column.addView(this)
        }
        text(getString(R.string.app_name), 30f)
        text(getString(R.string.tagline), 20f)
        text(getString(R.string.intro))
        status = text(getString(R.string.stopped), 18f)
        text(getString(R.string.screen_access_heading), 20f)
        text(getString(R.string.screen_disclosure))
        column.addView(Button(this).apply {
            text = getString(R.string.review_accessibility)
            setOnClickListener {
                if (dialog != null) return@setOnClickListener
                showDialog(AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.accessibility_consent_title))
                    .setMessage(getString(R.string.accessibility_description))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                        openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    })
            }
        })
        text(getString(R.string.scopes_heading), 20f)
        read = CheckBox(this).apply { text = getString(R.string.scope_read); column.addView(this) }
        act = CheckBox(this).apply { text = getString(R.string.scope_act); column.addView(this) }
        text(getString(R.string.scopes_help))
        start = Button(this).apply {
            text = getString(R.string.start_local)
            setOnClickListener {
                if (PhoneBridge.active || PhoneBridge.pendingActivation != null || permissionRequested || dialog != null) return@setOnClickListener
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionRequested = true
                    render()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    return@setOnClickListener
                }
                if (!notificationsVisible()) return@setOnClickListener
                if ((read.isChecked || act.isChecked) && PhoneBridge.accessibility == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.accessibility_required), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                showDialog(AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.start_consent_title))
                    .setMessage(getString(R.string.start_consent_body))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.start_confirm)) { _, _ ->
                        if (PhoneBridge.active || PhoneBridge.pendingActivation != null) return@setPositiveButton
                        if (!notificationsVisible()) return@setPositiveButton
                        if ((read.isChecked || act.isChecked) && PhoneBridge.accessibility == null) {
                            Toast.makeText(this@MainActivity, R.string.accessibility_required, Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        val selectedScopes = buildSet {
                            if (read.isChecked) add("screen.read")
                            if (act.isChecked) add("screen.act")
                        }
                        try {
                            val activation = PhoneBridge.requestActivation(selectedScopes)
                            PhoneBridge.statusMessage = getString(R.string.starting)
                            render()
                            startForegroundService(Intent(this@MainActivity, McpService::class.java)
                                .setAction("dev.phonemcp.START").putExtra("activation", activation))
                        } catch (_: RuntimeException) {
                            startRequested = false
                            PhoneBridge.revokeSession()
                            PhoneBridge.statusMessage = getString(R.string.start_failed)
                            render()
                        }
                    })
            }
            column.addView(this)
        }
        column.addView(Button(this).apply {
            text = getString(R.string.stop_revoke)
            setOnClickListener {
                dialog?.dismiss()
                startRequested = false
                // Revocation is immediate even before Service receives the stop intent.
                PhoneBridge.revokeSession()
                val serviceIntent = Intent(this@MainActivity, McpService::class.java)
                stopService(serviceIntent)
                PhoneBridge.statusMessage = getString(R.string.stopped_revoked)
                render()
            }
        })
        text(getString(R.string.connect_heading), 20f)
        text(getString(R.string.connect_help))
        token = text("").apply { typeface = android.graphics.Typeface.MONOSPACE }
        render()
    }
    private fun render() {
        if (!::status.isInitialized) return
        val active = PhoneBridge.active
        startRequested = PhoneBridge.pendingActivation != null
        status.text = getString(R.string.session_status,
            getString(if (active) R.string.active_label else R.string.stopped_label),
            PhoneBridge.statusMessage,
            getString(if (PhoneBridge.accessibility != null) R.string.accessibility_connected else R.string.accessibility_not_enabled))
        status.setTextColor(if (active) Color.rgb(0, 100, 40) else Color.DKGRAY)
        token.text = if (active) getString(R.string.bearer_token, PhoneBridge.token.orEmpty()) else getString(R.string.no_token)
        start.isEnabled = !active && !startRequested && !permissionRequested
        read.isEnabled = !active && !startRequested; act.isEnabled = !active && !startRequested
        if (active) { read.isChecked = "screen.read" in PhoneBridge.scopes; act.isChecked = "screen.act" in PhoneBridge.scopes }
    }
    private fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.create().also { shown ->
            shown.window?.let { dialogWindow ->
                protectWindow(dialogWindow)
                val original = dialogWindow.callback
                val touchGuard = TouchGuard()
                dialogWindow.callback = object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                        touchGuard.dispatch(event) { original.dispatchTouchEvent(it) }
                }
            }
            shown.setOnDismissListener { if (dialog === shown) dialog = null }
            shown.show()
        }
    }
    private fun protectWindow(target: Window) {
        target.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) target.setHideOverlayWindows(true)
    }
    /** Reject the entire gesture if any event is obscured, not just that event. */
    private class TouchGuard {
        private var rejected = false
        fun dispatch(event: MotionEvent, forward: (MotionEvent) -> Boolean): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) rejected = false
            val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                (Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)
            if (obscured && !rejected) {
                rejected = true
                // Clear any pressed control if an overlay appeared mid-gesture.
                if (event.actionMasked != MotionEvent.ACTION_DOWN) {
                    val cancel = MotionEvent.obtain(event)
                    try { cancel.action = MotionEvent.ACTION_CANCEL; forward(cancel) } finally { cancel.recycle() }
                }
            }
            if (rejected) return true
            return forward(event)
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        activityTouchGuard.dispatch(event) { super.dispatchTouchEvent(it) }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        permissionRequested = false
        render()
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notifications_granted, Toast.LENGTH_LONG).show()
        } else {
            showNotificationSettings(false)
        }
    }
    private fun notificationsVisible(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val channelBlocked = manager.getNotificationChannel("control")?.importance == NotificationManager.IMPORTANCE_NONE
        if (!manager.areNotificationsEnabled() || channelBlocked) {
            showNotificationSettings(channelBlocked)
            return false
        }
        return true
    }
    private fun showNotificationSettings(channelBlocked: Boolean) {
        showDialog(AlertDialog.Builder(this)
            .setTitle(R.string.notifications_required_title)
            .setMessage(R.string.notifications_required_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(if (channelBlocked) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                if (channelBlocked) intent.putExtra(Settings.EXTRA_CHANNEL_ID, "control")
                openSettings(intent)
            })
    }
    override fun onDestroy() { dialog?.dismiss(); dialog = null; super.onDestroy() }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
