package dev.phonemcp.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.phonemcp.core.LoopbackMcpServer
import java.security.SecureRandom

/** No boot receiver, sticky restart, stored token or remote activation. */
class McpService : Service() {
    private var server: LoopbackMcpServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var serviceToken: String? = null
    private val checkVisibility = object : Runnable {
        override fun run() {
            val token = serviceToken ?: return
            if (PhoneBridge.token != token || !PhoneBridge.active) return
            if (!SessionVisibility.available(this@McpService) ||
                (PhoneBridge.scopes.isNotEmpty() && PhoneBridge.accessibility == null)) {
                failSession(token, "Session stopped: notification visibility or Accessibility access was revoked.")
            } else handler.postDelayed(this, 1000)
        }
    }
    private fun failSession(token: String, message: String) {
        // A delayed old-server failure must never revoke a newer session.
        if (serviceToken != token || PhoneBridge.token != token) return
        PhoneBridge.revokeSession()
        PhoneBridge.statusMessage = message
        server?.close()
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "dev.phonemcp.STOP") {
            PhoneBridge.revokeSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != "dev.phonemcp.START") {
            if (server == null) { PhoneBridge.revokeSession(); stopSelf() }
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY
        if (!PhoneBridge.consumeActivation(intent.getStringExtra("activation"))) {
            // Stop invalidates queued starts. A stale intent must not cancel a newer local grant.
            if (PhoneBridge.pendingActivation == null) {
                PhoneBridge.statusMessage = "Start cancelled; no local activation grant."
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("control", "Phone MCP session", NotificationManager.IMPORTANCE_LOW))
        if (!SessionVisibility.available(this) ||
            (PhoneBridge.scopes.isNotEmpty() && PhoneBridge.accessibility == null)) {
            PhoneBridge.revokeSession()
            PhoneBridge.statusMessage = "Start refused: enable notifications and required Accessibility access."
            stopSelf()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction("dev.phonemcp.STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "control")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Phone MCP is active")
            .setContentText("Local control only • tap Stop to revoke access")
            .setContentIntent(open).setDeleteIntent(stop).setOngoing(true)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            serviceToken = token
            val candidate = LoopbackMcpServer(AndroidPhoneController(this, token), token) {
                handler.post { failSession(token, "Session stopped: local endpoint failed.") }
            }
            candidate.start()
            server = candidate
            PhoneBridge.token = token
            PhoneBridge.active = true
            PhoneBridge.statusMessage = "Active on 127.0.0.1:8765"
            handler.postDelayed(checkVisibility, 1000)
        } catch (e: Exception) {
            PhoneBridge.revokeSession()
            PhoneBridge.statusMessage = "Could not start local endpoint (${e.javaClass.simpleName})."
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        serviceToken = null
        PhoneBridge.revokeSession()
        server?.close()
        server = null
        if (PhoneBridge.statusMessage.startsWith("Active")) PhoneBridge.statusMessage = "Stopped; token revoked"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
