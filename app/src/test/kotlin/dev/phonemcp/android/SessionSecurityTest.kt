package dev.phonemcp.android

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** JVM Android framework simulation, not physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 35])
class SessionSecurityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val token = "a".repeat(43)
    @Before fun setup() {
        PhoneBridge.revokeSession()
        PhoneBridge.active = true; PhoneBridge.token = token
        PhoneBridge.scopes = emptySet(); PhoneBridge.accessibility = null
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.POST_NOTIFICATIONS")
        context.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("control", "Control", android.app.NotificationManager.IMPORTANCE_LOW))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    }
    @After fun cleanup() {
        PhoneBridge.active = false; PhoneBridge.token = null; PhoneBridge.scopes = emptySet(); PhoneBridge.accessibility = null; PhoneBridge.revokeSession()
    }
    private fun invoke(name: String) = AndroidPhoneController(context, token).execute(name, JSONObject())
    @Test fun statusRequiresActiveSession() {
        assertTrue(invoke("phone_status").getBoolean("active"))
        PhoneBridge.active = false
        assertTrue(invoke("phone_status").getString("error").startsWith("SESSION_STOPPED:"))
    }
    @Test fun oldServerCannotAdoptNewSessionTokenOrScopes() {
        val oldController = AndroidPhoneController(context, token)
        PhoneBridge.active = false; PhoneBridge.token = null
        PhoneBridge.token = "b".repeat(43); PhoneBridge.scopes = setOf("screen.read", "screen.act"); PhoneBridge.active = true
        for (tool in listOf("phone_status", "screen_inspect", "global_action")) {
            assertTrue(oldController.execute(tool, JSONObject()).getString("error").startsWith("SESSION_STOPPED:"))
        }
        assertTrue(AndroidPhoneController(context, PhoneBridge.token!!).execute("phone_status", JSONObject()).getBoolean("active"))
    }
    @Test fun lockAndScreenOffGateScreenAccessButNotStatus() {
        PhoneBridge.scopes = setOf("screen.read")
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        assertTrue(invoke("screen_inspect").getString("error").startsWith("DEVICE_LOCKED:"))
        assertFalse(invoke("phone_status").getBoolean("unlocked"))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        assertTrue(invoke("screen_inspect").getString("error").startsWith("DEVICE_LOCKED:"))
    }
    @Test fun scopesAndAccessibilityAreCheckedBeforeActions() {
        assertTrue(invoke("screen_inspect").getString("error").startsWith("SCOPE_DENIED:"))
        PhoneBridge.scopes = setOf("screen.read")
        assertTrue(invoke("screen_inspect").getString("error").startsWith("ACCESSIBILITY_DISABLED:"))
        assertTrue(invoke("global_action").getString("error").startsWith("SCOPE_DENIED:"))
        PhoneBridge.scopes = setOf("screen.act")
        assertTrue(invoke("node_click").getString("error").startsWith("SCOPE_DENIED:"))
    }
    @Test fun notificationStopRevokesBeforeDestruction() {
        val service = Robolectric.buildService(McpService::class.java).create().get()
        service.onStartCommand(Intent(context, McpService::class.java).setAction("dev.phonemcp.STOP"),0,1)
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token); assertTrue(PhoneBridge.scopes.isEmpty())
        service.onDestroy()
    }
    @Test fun nullOrUnknownStartDoesNotReactivate() {
        cleanup()
        val service = Robolectric.buildService(McpService::class.java).create().get()
        service.onStartCommand(null,0,1)
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
        service.onStartCommand(Intent(context, McpService::class.java),0,2)
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
        service.onDestroy()
    }
    @Test fun notificationLossImmediatelyRevokesOnAuthorization() {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        assertTrue(invoke("phone_status").getString("error").startsWith("SESSION_STOPPED:"))
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
    }
    @Test fun stopDisposesRetainedScreenSnapshot() {
        val accessibility = Robolectric.buildService(PhoneAccessibilityService::class.java).create().get()
        PhoneBridge.accessibility = accessibility
        val cls = PhoneAccessibilityService::class.java
        cls.getDeclaredField("capturedToken").apply { isAccessible = true }.set(accessibility, token)
        cls.getDeclaredField("capturedPackage").apply { isAccessible = true }.set(accessibility, "example.private.screen")
        cls.getDeclaredField("capturedAt").apply { isAccessible = true }.setLong(accessibility, 10L)
        PhoneBridge.revokeSession()
        assertNull(cls.getDeclaredField("capturedToken").apply { isAccessible = true }.get(accessibility))
        assertNull(cls.getDeclaredField("capturedPackage").apply { isAccessible = true }.get(accessibility))
        assertEquals(0L, cls.getDeclaredField("capturedAt").apply { isAccessible = true }.getLong(accessibility))
        accessibility.onDestroy()
    }
    @Test fun unavailableNotificationsRefuseStart() {
        cleanup()
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        val service = Robolectric.buildService(McpService::class.java).create().get()
        service.onStartCommand(Intent(context, McpService::class.java).setAction("dev.phonemcp.START")
            .putExtra("activation", PhoneBridge.requestActivation(emptySet())),0,1)
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
        service.onDestroy()
    }
    @Test fun bindFailureDoesNotPublishAuthority() {
        cleanup()
        java.net.ServerSocket().use { occupied ->
            occupied.bind(java.net.InetSocketAddress("127.0.0.1",8765))
            val service = Robolectric.buildService(McpService::class.java).create().get()
            service.onStartCommand(Intent(context, McpService::class.java).setAction("dev.phonemcp.START")
            .putExtra("activation", PhoneBridge.requestActivation(emptySet())),0,1)
            assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
            assertTrue(PhoneBridge.statusMessage.startsWith("Could not start"))
            service.onDestroy()
        }
    }
    @Test fun delayedOldFailureDoesNotRevokeNewAuthority() {
        val service = Robolectric.buildService(McpService::class.java).create().get()
        val old = "old-session"
        McpService::class.java.getDeclaredField("serviceToken").apply { isAccessible = true }.set(service, old)
        PhoneBridge.token = token; PhoneBridge.active = true
        McpService::class.java.getDeclaredMethod("failSession",String::class.java,String::class.java)
            .apply { isAccessible = true }.invoke(service, old, "old failure")
        assertTrue(PhoneBridge.active); assertEquals(token,PhoneBridge.token)
        service.onDestroy()
    }
    @Test fun stopInvalidatesQueuedActivationWithoutCancellingNewerGrant() {
        cleanup()
        val oldGrant = PhoneBridge.requestActivation(emptySet())
        PhoneBridge.revokeSession()
        val newGrant = PhoneBridge.requestActivation(emptySet())
        val service = Robolectric.buildService(McpService::class.java).create().get()
        service.onStartCommand(Intent(context, McpService::class.java).setAction("dev.phonemcp.START")
            .putExtra("activation", oldGrant),0,1)
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
        assertEquals(newGrant, PhoneBridge.pendingActivation)
        assertTrue(PhoneBridge.consumeActivation(newGrant))
        assertFalse(PhoneBridge.consumeActivation(newGrant))
        service.onDestroy()
    }
    @Test fun destructionRevokesEverything() {
        val service = Robolectric.buildService(McpService::class.java).create().get()
        PhoneBridge.scopes = setOf("screen.read", "screen.act")
        service.onDestroy()
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token); assertTrue(PhoneBridge.scopes.isEmpty())
    }
}
