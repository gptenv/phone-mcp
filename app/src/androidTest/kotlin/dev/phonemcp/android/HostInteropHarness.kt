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
        fun await(label: String, predicate: () -> Boolean) {
            val end = System.nanoTime() + 180_000_000_000L
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
                await("stopped") { !PhoneBridge.active }
                file("interop-stopped").writeText("stopped")
                await("restart requested") { file("interop-restart").exists() }
                start()
                assertNotEquals(first,PhoneBridge.token)
                file("interop-restarted").writeText("restarted")
                await("host finished") { file("interop-finish").exists() }
            }
        } finally {
            instrumentation.runOnMainSync { PhoneBridge.revokeSession(); context.stopService(Intent(context,McpService::class.java)) }
            files.forEach { file(it).delete() }
        }
    }
}
