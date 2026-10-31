package dev.phonemcp.android

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.pm.PackageManager
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** JVM window/lifecycle simulations; real overlay behavior still needs device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class UiSecurityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before fun setup() {
        PhoneBridge.active = false; PhoneBridge.token = null; PhoneBridge.scopes = emptySet()
        PhoneBridge.accessibility = null; PhoneBridge.revokeSession(); PhoneBridge.statusMessage = "Stopped"
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.HIDE_OVERLAY_WINDOWS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        activity = controller.get()
    }
    @After fun cleanup() {
        if (::activity.isInitialized) controller.pause().stop().destroy()
        PhoneBridge.active = false; PhoneBridge.token = null; PhoneBridge.scopes = emptySet()
        PhoneBridge.accessibility = null; PhoneBridge.revokeSession(); PhoneBridge.statusMessage = "Stopped"
    }
    private fun allViews(view: View = activity.window.decorView): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
    private fun button(resource: Int) = allViews().filterIsInstance<Button>().single { it.text.toString() == activity.getString(resource) }
    private fun press(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
        // AlertController posts button listeners and dismissal to its main Handler.
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun consent(): AlertDialog {
        button(R.string.start_local).performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also { assertTrue(it.isShowing) }
    }
    private fun event(action: Int, flags: Int): MotionEvent = MotionEvent.obtain(
        0L, 1L, action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
        arrayOf(MotionEvent.PointerCoords().apply { x = 1f; y = 1f; pressure = 1f; size = 1f }),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
    private fun dispatch(action: Int, flags: Int, target: (MotionEvent) -> Boolean): Boolean {
        val event = event(action, flags)
        return try { target(event) } finally { event.recycle() }
    }
    private fun activityGuard(): Any = MainActivity::class.java.getDeclaredField("activityTouchGuard").run {
        isAccessible = true; get(activity)
    }
    private fun dialogGuard(dialog: AlertDialog): Any = dialog.window!!.callback.javaClass.declaredFields
        .map { it.isAccessible = true; it.get(dialog.window!!.callback) }
        .single { it?.javaClass?.simpleName == "TouchGuard" }!!
    private fun exerciseGuard(guard: Any, flags: Int) {
        val forwarded = mutableListOf<Int>()
        val forward: (MotionEvent) -> Boolean = { forwarded.add(it.actionMasked); false }
        val method = guard.javaClass.getDeclaredMethod("dispatch", MotionEvent::class.java, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }
        fun send(action: Int, mask: Int) = dispatch(action, mask) { method.invoke(guard, it, forward) as Boolean }
        assertTrue(send(MotionEvent.ACTION_DOWN, flags))
        assertTrue(send(MotionEvent.ACTION_UP, 0))
        assertTrue(forwarded.isEmpty())
        assertFalse(send(MotionEvent.ACTION_DOWN, 0)) // next clean gesture works
        assertTrue(send(MotionEvent.ACTION_MOVE, flags))
        assertTrue(send(MotionEvent.ACTION_UP, 0))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), forwarded)
    }

    @Test fun initialSessionIsInactiveAndAllScopesOffWithSecureWindow() {
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token); assertTrue(PhoneBridge.scopes.isEmpty())
        assertTrue(allViews().filterIsInstance<CheckBox>().all { !it.isChecked })
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
    @Test fun cancelConsentNeverStartsServiceOrGrantsScope() {
        val dialog = consent()
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        press(dialog, AlertDialog.BUTTON_NEGATIVE)
        assertFalse(dialog.isShowing); assertFalse(PhoneBridge.active); assertTrue(PhoneBridge.scopes.isEmpty())
        assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun repeatedStartCannotStackConsentDialogs() {
        val first = consent()
        button(R.string.start_local).performClick()
        assertSame(first, ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(PhoneBridge.active); assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun rotationCancelsPendingConsentWithoutStarting() {
        val first = consent()
        controller.recreate()
        activity = controller.get()
        assertFalse(first.isShowing); assertFalse(PhoneBridge.active); assertTrue(PhoneBridge.scopes.isEmpty())
        assertNull(shadowOf(activity).nextStartedService)
        assertTrue(button(R.string.start_local).isEnabled)
    }
    @Test fun statusOnlyConsentUsesExplicitStartAndPreventsDuplicateRequest() {
        press(consent(), AlertDialog.BUTTON_POSITIVE)
        val request = shadowOf(activity).nextStartedService
        assertEquals("dev.phonemcp.START", request.action)
        assertFalse(request.getStringExtra("activation").isNullOrBlank())
        assertEquals(PhoneBridge.pendingActivation, request.getStringExtra("activation"))
        assertTrue(PhoneBridge.scopes.isEmpty())
        assertFalse(button(R.string.start_local).isEnabled)
        button(R.string.start_local).performClick()
        assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun stopInvalidatesPendingActivationBeforeServiceCanStart() {
        press(consent(), AlertDialog.BUTTON_POSITIVE)
        val request = shadowOf(activity).nextStartedService
        val activation = request.getStringExtra("activation")
        assertNotNull(PhoneBridge.pendingActivation)
        button(R.string.stop_revoke).performClick()
        assertNull(PhoneBridge.pendingActivation)
        assertFalse(PhoneBridge.consumeActivation(activation))
        assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token); assertTrue(PhoneBridge.scopes.isEmpty())
    }
    @Test fun activationTicketsAreOneUseAndOldTicketCannotConsumeNewConsent() {
        val first = PhoneBridge.requestActivation(setOf("screen.read"))
        PhoneBridge.revokeSession()
        val second = PhoneBridge.requestActivation(emptySet())
        assertNotEquals(first, second)
        assertFalse(PhoneBridge.consumeActivation(first))
        assertEquals(second, PhoneBridge.pendingActivation)
        assertTrue(PhoneBridge.consumeActivation(second))
        assertFalse(PhoneBridge.consumeActivation(second))
    }
    @Test fun stopRevokesSessionAndCancelsOutstandingConsent() {
        val dialog = consent()
        PhoneBridge.active = true; PhoneBridge.token = "secret"; PhoneBridge.scopes = setOf("screen.read")
        button(R.string.stop_revoke).performClick()
        assertFalse(dialog.isShowing); assertFalse(PhoneBridge.active); assertNull(PhoneBridge.token)
        assertTrue(PhoneBridge.scopes.isEmpty())
    }
    @Test fun disabledNotificationsCannotStartSession() {
        shadowOf(activity.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        button(R.string.start_local).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.notifications_required_title), shadowOf(dialog).title.toString())
        assertFalse(PhoneBridge.active); assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun disabledSessionChannelCannotStartSession() {
        activity.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("control", "Session", NotificationManager.IMPORTANCE_NONE))
        button(R.string.start_local).performClick()
        assertEquals(activity.getString(R.string.notifications_required_title), shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title.toString())
        assertFalse(PhoneBridge.active); assertNull(shadowOf(activity).nextStartedService)
    }
    @Test @Config(sdk = [35]) fun notificationDenialAndGrantNeverAutoStart() {
        activity.onRequestPermissionsResult(1, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_DENIED))
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.notifications_required_title), shadowOf(dialog).title.toString())
        press(dialog, AlertDialog.BUTTON_NEGATIVE)
        activity.onRequestPermissionsResult(1, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals("Notifications granted. Press Start to begin a new session.",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertFalse(PhoneBridge.active); assertTrue(PhoneBridge.scopes.isEmpty())
        assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun activityRejectsFullyObscuredAndMidGestureTouches() {
        assertTrue(dispatch(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED, activity::dispatchTouchEvent))
        exerciseGuard(activityGuard(), MotionEvent.FLAG_WINDOW_IS_OBSCURED)
    }
    @Test fun dialogRejectsFullyObscuredAndMidGestureTouches() {
        val dialog = consent()
        assertTrue(dispatch(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED, dialog.window!!.callback::dispatchTouchEvent))
        assertTrue(dialog.isShowing); assertFalse(PhoneBridge.active)
        exerciseGuard(dialogGuard(dialog), MotionEvent.FLAG_WINDOW_IS_OBSCURED)
    }
    @Test @Config(sdk = [35]) fun api29PlusRejectsPartiallyObscuredActivityAndDialogTouches() {
        val flag = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        assertTrue(dispatch(MotionEvent.ACTION_DOWN, flag, activity::dispatchTouchEvent))
        exerciseGuard(activityGuard(), flag)
        val dialog = consent()
        assertTrue(dispatch(MotionEvent.ACTION_DOWN, flag, dialog.window!!.callback::dispatchTouchEvent))
        exerciseGuard(dialogGuard(dialog), flag)
        assertTrue(dialog.isShowing); assertFalse(PhoneBridge.active)
    }
}
