package dev.phonemcp.android

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real framework/accessibility tests. Refuses non-emulators; never run on a personal phone. */
@RunWith(AndroidJUnit4::class)
class DeviceAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var automation: UiAutomation
    private var mainActivity: Activity? = null
    private var settingsSaved = false
    private var originalServices = "null"
    private var originalEnabled = "null"
    private var notificationsWereGranted = false
    private var permissionChanged = false
    private val fixturePackage = "dev.phonemcp.fixture"
    private val fixtureComponent get() = "$fixturePackage/.FixtureActivity"

    // UiAutomation executes argv directly, not through a shell parser: quoting is literal.
    private fun safeArg(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_./:$+-]+"))) { "Unexpected test command identifier" }
        return value
    }
    private fun shell(command: String): String {
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "phone-mcp-test-shell").apply { isDaemon = true }
        }
        val work = executor.submit<String> {
            val fd = automation.executeShellCommand(command)
            descriptor.set(fd)
            if (cancelled.get()) { fd.close(); throw InterruptedException("Command cancelled") }
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() < 65536) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 65536 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8").trim()
            }
        }
        return try { work.get(10, TimeUnit.SECONDS) }
        catch (timeout: TimeoutException) {
            cancelled.set(true)
            try { descriptor.get()?.close() } finally { work.cancel(true) }
            throw AssertionError("Emulator command timed out: ${command.substringBefore(' ')}", timeout)
        } finally { executor.shutdownNow() }
    }
    private fun waitUntil(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        if (description.contains("accessibility connection") && ::automation.isInitialized) {
            // Dedicated synthetic emulator only. Do not collect general logcat or screen contents.
            for ((label, command) in listOf(
                "enabled services" to "settings get secure enabled_accessibility_services",
                "accessibility enabled" to "settings get secure accessibility_enabled",
                "accessibility manager" to "dumpsys accessibility",
                "accessibility/runtime errors" to "logcat -d -t 150 -s AccessibilityManagerService:V AccessibilityService:V AndroidRuntime:E")) {
                try { System.err.println("PHONE_MCP_TEST_DIAGNOSTIC $label\n" + shell(command).take(24000)) }
                catch (error: Exception) { System.err.println("PHONE_MCP_TEST_DIAGNOSTIC $label unavailable: ${error.javaClass.simpleName}") }
            }
        }
        fail("Timed out waiting for $description")
    }
    @Before fun setup() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
            "These tests change accessibility settings and are restricted to a dedicated emulator"
        }
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        originalServices = shell("settings get secure enabled_accessibility_services")
        originalEnabled = shell("settings get secure accessibility_enabled")
        check(originalEnabled in setOf("null", "0", "1")) { "Could not read emulator accessibility state" }
        settingsSaved = true
        notificationsWereGranted = target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !notificationsWereGranted) {
            shell("pm grant ${safeArg(target.packageName)} android.permission.POST_NOTIFICATIONS")
            permissionChanged = true
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val component = ComponentName(target, PhoneAccessibilityService::class.java).flattenToString()
        val enabled = originalServices.takeUnless { it == "null" || it.isBlank() }
            ?.split(':').orEmpty().toMutableSet().apply { add(component) }.joinToString(":")
        shell("settings put secure enabled_accessibility_services ${safeArg(enabled)}")
        shell("settings put secure accessibility_enabled 1")
        mainActivity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil("Phone MCP accessibility connection") { PhoneBridge.accessibility != null }
    }
    @After fun cleanup() {
        if (!::automation.isInitialized) return // Refused non-emulator: perform no cleanup actions there.
        // Always restore emulator global settings, including when setup or an assertion fails.
        try {
            instrumentation.runOnMainSync {
                PhoneBridge.revokeSession()
                target.stopService(Intent(target, McpService::class.java))
                mainActivity?.finish()
            }
        } finally {
            if (::automation.isInitialized) {
                try {
                    if (settingsSaved) {
                        fun restore(key: String, value: String) {
                            if (value == "null" || value.isBlank()) shell("settings delete secure $key")
                            else shell("settings put secure $key ${safeArg(value)}")
                        }
                        try { restore("enabled_accessibility_services", originalServices) }
                        finally { restore("accessibility_enabled", originalEnabled) }
                    }
                } finally {
                    try {
                        if (permissionChanged) shell("pm revoke ${safeArg(target.packageName)} android.permission.POST_NOTIFICATIONS")
                    } finally {
                        shell("am start -n ${safeArg(fixtureComponent)} --ez finish true")
                    }
                }
            }
        }
    }
    private fun startSession(scopes: Set<String>): AndroidPhoneController {
        instrumentation.runOnMainSync {
            PhoneBridge.revokeSession()
            val activation = PhoneBridge.requestActivation(scopes)
            target.startForegroundService(Intent(target, McpService::class.java)
                .setAction("dev.phonemcp.START").putExtra("activation", activation))
        }
        waitUntil("authorized local endpoint") { PhoneBridge.active && PhoneBridge.token != null }
        return AndroidPhoneController(target, PhoneBridge.token!!)
    }
    private fun inspectFixture(controller: AndroidPhoneController, expectedText: String? = null): JSONObject {
        var latest = JSONObject()
        try {
            waitUntil("fixture accessibility tree${expectedText?.let { " containing $it" }.orEmpty()}") {
                latest = controller.execute("screen_inspect", JSONObject())
                !latest.has("error") && latest.optString("packageName") == fixturePackage &&
                    (expectedText == null || latest.toString().contains(expectedText))
            }
        } catch (failure: AssertionError) {
            val safePackage = latest.optString("packageName").take(160).filter { it.isLetterOrDigit() || it in "._" }
            @Suppress("DEPRECATION")
            val actualRootPackage = try {
                val root = automation.rootInActiveWindow
                try { root?.packageName?.toString().orEmpty().take(160).filter { it.isLetterOrDigit() || it in "._" } }
                finally { root?.recycle() }
            } catch (_: Exception) { "unavailable" }
            // Controller errors are sanitized constants. Never print nodes, labels, input values or the token.
            throw AssertionError("Fixture not readable: error=${latest.optString("error").take(240)}; package=$safePackage; " +
                "active=${PhoneBridge.active}; accessibility=${PhoneBridge.accessibility != null}; " +
                "actualRootPackage=$actualRootPackage; expectedFixture=$fixturePackage", failure)
        }
        return latest
    }
    private fun showFixture(mode: String = "notes") {
        val launch = shell("am start -n ${safeArg(fixtureComponent)} --es mode ${safeArg(mode)}")
        // This command contains only a validated test component and fixed mode, never credentials.
        check(!launch.contains("Error", ignoreCase = true) && !launch.contains("Exception")) {
            "Fixture launch failed: ${launch.take(2000)}"
        }
        System.err.println("PHONE_MCP_FIXTURE_LAUNCH ${launch.take(2000)}")
        automation.waitForIdle(500, 10_000)
    }
    @Test fun standaloneFixtureForegroundSmoke() {
        val controller = startSession(setOf("screen.read"))
        showFixture()
        val tree = inspectFixture(controller, "MCP public fixture label")
        assertEquals("Separate fixture must own the accessible foreground root", fixturePackage, tree.getString("packageName"))
    }
    @Test fun realTreeRedactsSecretsAndFreshClickInvalidatesOldHandle() {
        val controller = startSession(setOf("screen.read", "screen.act"))
        showFixture()
        val tree = inspectFixture(controller, "MCP public fixture label")
        val encoded = tree.toString()
        assertFalse(encoded.contains("PRIVATE_EDIT_SECRET_7832"))
        assertFalse(encoded.contains("PRIVATE_PASSWORD_SECRET_3984"))
        assertFalse(encoded.contains("PRIVATE_LOGIN_SECRET_1204"))
        assertFalse(encoded.contains("PRIVATE_EXTERNAL_LOGIN_SECRET_4912"))
        val nodes = tree.getJSONArray("nodes")
        val button = (0 until nodes.length()).map(nodes::getJSONObject)
            .single { it.optString("text") == "MCP public fixture button" && it.optBoolean("clickable") }
        val id = button.getString("nodeId")
        val result = controller.execute("node_click", JSONObject().put("nodeId", id))
        assertFalse(result.toString(), result.has("error"))
        assertTrue(result.getBoolean("accepted"))
        inspectFixture(controller, "MCP button clicked")
        val stale = controller.execute("node_click", JSONObject().put("nodeId", id))
        assertTrue(stale.toString(), stale.getString("error").startsWith("STALE_NODE:"))
    }
    @Test fun realReadOnlySessionCannotClickOrNavigate() {
        val controller = startSession(setOf("screen.read"))
        showFixture()
        val tree = inspectFixture(controller, "MCP public fixture label")
        val nodes = tree.getJSONArray("nodes")
        val button = (0 until nodes.length()).map(nodes::getJSONObject)
            .single { it.optString("text") == "MCP public fixture button" }
        for ((name, args) in listOf(
            "node_click" to JSONObject().put("nodeId", button.getString("nodeId")),
            "node_scroll" to JSONObject().put("nodeId", button.getString("nodeId")).put("direction", "forward"),
            "node_set_text" to JSONObject().put("nodeId", button.getString("nodeId")).put("text", "ordinary notes"),
            "global_action" to JSONObject().put("action", "back"))) {
            val denied = controller.execute(name, args)
            assertTrue(denied.toString(), denied.getString("error").startsWith("SCOPE_DENIED:"))
        }
        assertFalse(inspectFixture(controller).toString().contains("MCP button clicked"))
    }
    @Test fun nonSensitiveTextReplacementAndClearNeverExposeEditableValues() {
        val controller = startSession(setOf("screen.read", "screen.act"))
        showFixture()
        fun editable(tree: JSONObject): JSONObject {
            val nodes = tree.getJSONArray("nodes")
            val fields = (0 until nodes.length()).map(nodes::getJSONObject).filter { it.optBoolean("setTextAllowed") }
            assertEquals("Password, login-hinted, and externally credential-labelled fields must have no editable handles", 1, fields.size)
            return fields.single().also {
                assertTrue(it.getBoolean("textRedacted")); assertFalse(it.has("text")); assertFalse(it.has("description"))
                assertFalse(it.getBoolean("clickable"))
            }
        }
        val initial = inspectFixture(controller, "MCP public fixture label")
        val firstId = editable(initial).getString("nodeId")
        val result = controller.execute("node_set_text", JSONObject().put("nodeId", firstId).put("text", "ordinary notes"))
        assertFalse(result.toString(), result.has("error")); assertTrue(result.getBoolean("accepted"))
        val updated = inspectFixture(controller, "MCP replacement accepted")
        assertFalse(updated.toString().contains("ordinary notes"))
        val stale = controller.execute("node_set_text", JSONObject().put("nodeId", firstId).put("text", "should not run"))
        assertTrue(stale.toString(), stale.getString("error").startsWith("STALE_NODE:"))
        val fresh = editable(inspectFixture(controller)).getString("nodeId")
        val clear = controller.execute("node_set_text", JSONObject().put("nodeId", fresh).put("text", ""))
        assertFalse(clear.toString(), clear.has("error")); assertTrue(clear.getBoolean("accepted"))
        inspectFixture(controller, "MCP field cleared")
    }
    @Test fun wrongNodeTypesAndEditableClicksAreRejected() {
        val controller = startSession(setOf("screen.read", "screen.act"))
        showFixture()
        fun nodes(): List<JSONObject> {
            val tree = inspectFixture(controller).getJSONArray("nodes")
            return (0 until tree.length()).map(tree::getJSONObject)
        }
        val buttonId = nodes().single { it.optString("text") == "MCP public fixture button" }.getString("nodeId")
        val wrongText = controller.execute("node_set_text", JSONObject().put("nodeId", buttonId).put("text", "ordinary notes"))
        assertTrue(wrongText.toString(), wrongText.getString("error").startsWith("PROTECTED_NODE:"))
        val editableId = nodes().single { it.optBoolean("setTextAllowed") }.getString("nodeId")
        val click = controller.execute("node_click", JSONObject().put("nodeId", editableId))
        assertTrue(click.toString(), click.getString("error").startsWith("PROTECTED_NODE:"))
        val freshButton = nodes().single { it.optString("text") == "MCP public fixture button" }.getString("nodeId")
        val wrongScroll = controller.execute("node_scroll", JSONObject().put("nodeId", freshButton).put("direction", "forward"))
        assertTrue(wrongScroll.toString(), wrongScroll.getString("error").startsWith("PROTECTED_NODE:"))
        val fakePassword = controller.execute("node_set_text", JSONObject().put("nodeId", "password").put("text", "not permitted"))
        assertTrue(fakePassword.toString(), fakePassword.getString("error").startsWith("STALE_NODE:"))
    }
    @Test fun oneStepScrollUsesFreshHandlesAndCanReverse() {
        val controller = startSession(setOf("screen.read", "screen.act"))
        showFixture("scroll")
        fun scrollId(tree: JSONObject): String {
            val nodes = tree.getJSONArray("nodes")
            return (0 until nodes.length()).map(nodes::getJSONObject).single { it.optBoolean("scrollable") }.getString("nodeId")
        }
        val id = scrollId(inspectFixture(controller, "MCP scroll top"))
        val forward = controller.execute("node_scroll", JSONObject().put("nodeId", id).put("direction", "forward"))
        assertFalse(forward.toString(), forward.has("error")); assertTrue(forward.getBoolean("accepted"))
        inspectFixture(controller, "MCP scroll moved")
        val stale = controller.execute("node_scroll", JSONObject().put("nodeId", id).put("direction", "forward"))
        assertTrue(stale.toString(), stale.getString("error").startsWith("STALE_NODE:"))
        automation.waitForIdle(500, 10_000)
        val freshId = scrollId(inspectFixture(controller))
        val backward = controller.execute("node_scroll", JSONObject().put("nodeId", freshId).put("direction", "backward"))
        assertFalse(backward.toString(), backward.has("error")); assertTrue(backward.getBoolean("accepted"))
    }
    @Test @SdkSuppress(minSdkVersion = 36)
    fun api36SecondCredentialLabelPreventsTextHandle() {
        val controller = startSession(setOf("screen.read", "screen.act"))
        showFixture("multi_labels")
        val tree = inspectFixture(controller, "MCP multi label fixture")
        assertFalse(tree.toString().contains("PRIVATE_MULTI_LABEL_SECRET_5284"))
        val nodes = tree.getJSONArray("nodes")
        assertTrue("A later credential label must block a generic field", (0 until nodes.length())
            .map(nodes::getJSONObject).none { it.optBoolean("setTextAllowed") })
    }
    @Test fun ownTokenScreenIsNeverExposed() {
        val controller = startSession(setOf("screen.read", "screen.act"))
        var denied = JSONObject()
        waitUntil("own-screen privacy gate") {
            denied = controller.execute("screen_inspect", JSONObject())
            denied.optString("error").startsWith("PROTECTED_SCREEN:")
        }
        val action = controller.execute("global_action", JSONObject().put("action", "home"))
        assertTrue(action.toString(), action.getString("error").startsWith("PROTECTED_SCREEN:"))
        assertFalse(denied.toString().contains(PhoneBridge.token!!))
    }
}
