package dev.phonemcp.android

import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/** Run only on a dedicated test device/emulator; changes its notification permission. */
@RunWith(AndroidJUnit4::class)
class DeviceSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var dedicatedEmulator = false
    @Before fun notifications() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Dedicated emulator only" }
        dedicatedEmulator = true
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
    }
    @After fun stop() {
        if (!dedicatedEmulator) return
        instrumentation.runOnMainSync {
            PhoneBridge.revokeSession()
            context.stopService(Intent(context, McpService::class.java))
        }
    }
    private fun awaitState(expected: Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (PhoneBridge.active != expected && System.nanoTime() < end) Thread.sleep(20)
        assertEquals("Session lifecycle did not settle: ${PhoneBridge.statusMessage}", expected, PhoneBridge.active)
    }
    private fun rpc(token: String): String = Socket("127.0.0.1",8765).use { socket ->
        socket.soTimeout = 4000
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"phone_status","arguments":{}}}""".toByteArray()
        val head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().write(head.toByteArray()); socket.getOutputStream().write(body)
        socket.getInputStream().bufferedReader().readText()
    }
    @Test fun secureUiAndRevokedSessionStayInactiveAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token); assertTrue(PhoneBridge.scopes.isEmpty())
            }
            scenario.recreate()
            scenario.onActivity { assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token) }
        }
    }
    @Test fun notificationDeleteIntentRevokesSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.startForegroundService(Intent(it, McpService::class.java).setAction("dev.phonemcp.START")
                .putExtra("activation", PhoneBridge.requestActivation(emptySet()))) }
            awaitState(true)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var posted = manager.activeNotifications.firstOrNull { it.id == 1 }
            while (posted == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
                posted = manager.activeNotifications.firstOrNull { it.id == 1 }
            }
            assertNotNull("Active session must post its visible Stop notification", posted)
            val notification = posted!!.notification
            assertTrue(notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertNotNull("Dismissal must have a Stop path", notification.deleteIntent)
            notification.deleteIntent.send()
            awaitState(false)
            assertNull(PhoneBridge.token)
        }
    }
    @Test fun localStartStopRotationAndActualHttpAuthentication() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.startForegroundService(Intent(it, McpService::class.java).setAction("dev.phonemcp.START").putExtra("activation", PhoneBridge.requestActivation(emptySet()))) }
            awaitState(true)
            val first = PhoneBridge.token!!
            assertEquals(43, first.length)
            val response = rpc(first)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("\\\"active\\\":true"))
            scenario.onActivity { it.startService(Intent(it, McpService::class.java).setAction("dev.phonemcp.STOP")) }
            awaitState(false)
            assertNull(PhoneBridge.token); assertTrue(PhoneBridge.scopes.isEmpty())
            // Wait for Android to finish destroying the old endpoint before a new local activation.
            Thread.sleep(200)
            scenario.onActivity { it.startForegroundService(Intent(it, McpService::class.java).setAction("dev.phonemcp.START").putExtra("activation", PhoneBridge.requestActivation(emptySet()))) }
            awaitState(true)
            assertTrue("Restart must rotate the token", first != PhoneBridge.token)
            assertTrue(rpc(first).startsWith("HTTP/1.1 401"))
            assertTrue(rpc(PhoneBridge.token!!).startsWith("HTTP/1.1 200"))
        }
    }
}
