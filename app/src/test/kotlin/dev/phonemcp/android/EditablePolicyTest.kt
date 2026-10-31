package dev.phonemcp.android

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

/** Pure policy tests: no Android UI simulation or editable value inspection. */
class EditablePolicyTest {
    private fun permitted(type: Int = InputType.TYPE_CLASS_TEXT, password: Boolean = false,
        sensitive: Boolean = false, editable: Boolean = true, enabled: Boolean = true,
        action: Boolean = true, vararg labels: String) =
        EditablePolicy.permits(type, password, sensitive, editable, enabled, action, labels.toList())
    @Test fun normalAndMultilineNotesAreEligible() {
        assertTrue(permitted(labels = arrayOf("notes_field", "Write a note")))
        assertTrue(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }
    @Test fun passwordVariationsAreNeverEligibleEvenWhenPasswordFlagMissing() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val type = InputType.TYPE_CLASS_TEXT or variation
            assertTrue(EditablePolicy.passwordInputType(type))
            assertFalse(permitted(type))
        }
        val numeric = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(EditablePolicy.passwordInputType(numeric)); assertFalse(permitted(numeric))
    }
    @Test fun explicitSensitiveFlagsAndUnsupportedTargetsAreDenied() {
        assertFalse(permitted(password = true)); assertFalse(permitted(sensitive = true))
        assertFalse(permitted(editable = false)); assertFalse(permitted(enabled = false)); assertFalse(permitted(action = false))
        assertFalse(permitted(InputType.TYPE_CLASS_NUMBER)); assertFalse(permitted(InputType.TYPE_NULL))
        assertFalse(permitted(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }
    @Test fun credentialLikeIdsHintsDescriptionsAndContainersAreDenied() {
        for (label in listOf("passwordField", "PIN", "otp_code", "accessToken", "secret", "AUTH",
            "loginForm", "username", "account", "email", "verification", "cardNumber", "CVV")) {
            assertTrue(label, EditablePolicy.credentialMetadata(listOf(label)))
            assertFalse(label, permitted(labels = arrayOf(label)))
        }
    }
    @Test fun oversizedMetadataFailsClosed() {
        assertFalse(permitted(labels = arrayOf("n".repeat(8193))))
    }
    @Test fun allAssociatedLabelsParticipateInCredentialPolicy() {
        assertTrue(EditablePolicy.labelMetadataAllowed(listOf(listOf("Notes"), listOf("Message body"))))
        assertFalse(EditablePolicy.labelMetadataAllowed(listOf(listOf("Notes"), listOf("PIN"))))
        assertFalse(EditablePolicy.labelMetadataAllowed(listOf(listOf("Notes"), listOf("username"))))
    }
    @Test fun excessLabelCountAndAggregateMetadataFailClosed() {
        assertFalse(EditablePolicy.labelMetadataAllowed(List(EditablePolicy.MAX_LABELS + 1) { listOf("Notes") }))
        assertFalse(EditablePolicy.labelMetadataAllowed(List(5) { listOf("n".repeat(8192)) }))
        assertFalse(EditablePolicy.labelMetadataAllowed(listOf(listOf("n".repeat(8193)))))
    }

}
