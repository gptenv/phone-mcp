package dev.phonemcp.android

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: field label changes alter identity; editable values do not participate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class EditableFingerprintTest {
    @Suppress("DEPRECATION")
    @Test fun safeMetadataChangesInvalidateIdentityButValueChangesDoNot() {
        val service = Robolectric.buildService(PhoneAccessibilityService::class.java).create()
        val node = AccessibilityNodeInfo.obtain().apply {
            isEditable = true; isEnabled = true; isVisibleToUser = true
            inputType = InputType.TYPE_CLASS_TEXT
            className = "android.widget.EditText"; packageName = "dev.example.notes"
            setBoundsInScreen(Rect(0, 0, 300, 100))
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            hintText = "Title"; contentDescription = "Notes title"
            text = "synthetic private value one"
        }
        try {
            val method = PhoneAccessibilityService::class.java.getDeclaredMethod("fingerprint",
                AccessibilityNodeInfo::class.java, java.lang.Boolean.TYPE, kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }
            val deadline: () -> Unit = {}
            fun digest() = method.invoke(service.get(), node, false, deadline) as ByteArray
            val initial = digest()
            node.text = "different synthetic private value two"
            assertArrayEquals("Values must never affect the retained fingerprint", initial, digest())
            node.hintText = "Message body"
            val hintChanged = digest()
            assertFalse("Recycled field hint must invalidate its old handle", initial.contentEquals(hintChanged))
            node.contentDescription = "Notes message"
            assertFalse("Recycled field description must invalidate its old handle", hintChanged.contentEquals(digest()))
        } finally { node.recycle(); service.destroy() }
    }
}
