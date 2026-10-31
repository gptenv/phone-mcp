package dev.phonemcp.android

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Diagnostic only: manually ordered callbacks do not establish Android lifecycle ordering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 35])
class OldInstanceDestroyTicketDiagnostic {
    @Before fun setup() { PhoneBridge.revokeSession(); PhoneBridge.accessibility = null }
    @After fun cleanup() { PhoneBridge.revokeSession(); PhoneBridge.accessibility = null }

    // Expected to fail against the review snapshot. An unstarted old service is a
    // deliberately artificial model, not evidence that Android delivers this order.
    @Test fun oldInstanceDestroyDoesNotEraseNewPendingTicket() {
        val old = Robolectric.buildService(McpService::class.java).create()
        val ticket = PhoneBridge.requestActivation(emptySet())
        old.destroy()
        assertEquals("Teardown should preserve a newer grant", ticket, PhoneBridge.pendingActivation)
        assertFalse(PhoneBridge.active)
        assertNull(PhoneBridge.token)
    }

    @Test fun explicitRevokeInvalidatesCurrentTicket() {
        val ticket = PhoneBridge.requestActivation(emptySet())
        PhoneBridge.revokeSession()
        assertNull(PhoneBridge.pendingActivation)
        assertFalse(PhoneBridge.consumeActivation(ticket))
        assertFalse(PhoneBridge.active)
        assertNull(PhoneBridge.token)
    }
}
