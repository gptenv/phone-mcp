package dev.phonemcp.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** A session may not continue if its local ongoing Stop control is hidden. */
internal object SessionVisibility {
    fun available(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permission = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = manager.getNotificationChannel("control")
        return permission && manager.areNotificationsEnabled() && channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
