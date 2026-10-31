package dev.phonemcp.android

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.Socket
import java.net.InetSocketAddress
import android.system.ErrnoException
import android.system.OsConstants
import org.json.JSONObject

/** Opt-in dedicated-emulator bridge for a real host MCP SDK; no production hooks. */
@RunWith(AndroidJUnit4::class)
class HostInteropHarness {
    @Test fun hostClientAcrossStopAndRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hostInterop") == "true")
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Dedicated emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = listOf("interop-token", "interop-stop", "interop-stopped", "interop-restart", "interop-restarted", "interop-finish")
        fun file(name: String) = File(context.filesDir,name)
        fun await(label: String, timeoutMillis: Long = 180_000, predicate: () -> Boolean) {
            val end = System.nanoTime() + timeoutMillis * 1_000_000L
            while (!predicate() && System.nanoTime() < end) Thread.sleep(100)
            assertTrue("Host coordination timed out: $label",predicate())
        }
        files.forEach { file(it).delete() }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { FileInputStream(it.fileDescriptor).readBytes() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun start() {
                    scenario.onActivity {
                        val ticket = PhoneBridge.requestActivation(emptySet())
                        it.startForegroundService(Intent(it,McpService::class.java).setAction("dev.phonemcp.START").putExtra("activation",ticket))
                    }
                    await("start") { PhoneBridge.active }
                    file("interop-token").writeText(PhoneBridge.token!!)
                }
                start()
                val first = PhoneBridge.token
                await("stop requested") { file("interop-stop").exists() }
                scenario.onActivity { PhoneBridge.revokeSession(); it.stopService(Intent(it,McpService::class.java)) }
                await("processed revocation", 10_000) { !PhoneBridge.active && PhoneBridge.token == null }
                // A host adb-forward EOF is not proof of device-side listener closure.
                await("device-local ECONNREFUSED", 10_000) { deviceListenerRefusesConnection() }
                file("interop-stopped").writeText(JSONObject()
                    .put("revocation_processed", true).put("active", false).put("token_present", false)
                    .put("listener_closed", true).put("connection_error", "ECONNREFUSED")
                    .put("device_api", Build.VERSION.SDK_INT).toString())
                await("restart requested") { file("interop-restart").exists() }
                start()
                assertTrue("Restart must rotate the token", first != PhoneBridge.token)
                file("interop-restarted").writeText("restarted")
                await("host finished") { file("interop-finish").exists() }
            }
        } finally {
            instrumentation.runOnMainSync { PhoneBridge.revokeSession(); context.stopService(Intent(context,McpService::class.java)) }
            files.forEach { file(it).delete() }
        }
    }
    private fun deviceListenerRefusesConnection(): Boolean {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8765), 250) }
            return false // A listener accepted the connection: no closure proof.
        } catch (error: IOException) {
            var cause: Throwable? = error
            repeat(8) {
                val current = cause ?: return false
                if (current is ErrnoException && current.errno == OsConstants.ECONNREFUSED) return true
                cause = current.cause
            }
            return false // Timeout/reset/unclassified error must never certify closure.
        }
    }

}
