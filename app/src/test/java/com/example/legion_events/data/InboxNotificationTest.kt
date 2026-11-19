/*
 * InboxNotificationTest.kt
 *
 * Purpose: Verify cancellation notice mapping while preserving lottery results and recipient isolation.
 * Role: Local JVM tests at the Firestore document-to-model boundary.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.CancellationNotification
import com.example.legion_events.model.LotteryNotification
import com.example.legion_events.model.LotteryResult
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Checks only saved document values, without a network connection or listener. */
class InboxNotificationTest {
    @Test fun cancellationNoticesKeepTheSavedReservationIdAndCompletionTime() {
        assertEquals(CancellationNotification("notice", "booking #1", Timestamp(10, 0)),
            document().toInboxNotification("alice"))
    }

    @Test fun lotteryWinsAndLossesStillMapToTheirOriginalModels() {
        assertEquals(LotteryNotification("notice", LotteryResult.WON, "offer", Timestamp(10, 0)),
            document(mapOf("type" to "lottery_won", "offerId" to "offer")).toInboxNotification("alice"))
        assertEquals(LotteryNotification("notice", LotteryResult.LOST, null, Timestamp(10, 0)),
            document(mapOf("type" to "lottery_lost", "offerId" to "ignored")).toInboxNotification("alice"))
    }

    @Test fun foreignIncompleteAndUnsupportedDocumentsAreNotDisplayed() {
        for (overrides in listOf(mapOf("toUserId" to "bob"), mapOf("toUserId" to null),
            mapOf("createdAt" to "tomorrow"), mapOf("reservationId" to " "),
            mapOf("reservationId" to "nested/path"), mapOf("reservationId" to null), mapOf("type" to "unrelated"))) {
            assertNull(document(overrides).toInboxNotification("alice"))
        }
    }

    /** Supplies a committed cancellation notice with selected malformed or alternate fields. */
    private fun document(overrides: Map<String, Any?> = emptyMap()): DocumentSnapshot {
        val fields = mapOf("toUserId" to "alice", "type" to "reservation_cancelled",
            "reservationId" to "booking #1", "createdAt" to Timestamp(10, 0)) + overrides
        return mock(DocumentSnapshot::class.java).also { document ->
            `when`(document.id).thenReturn("notice")
            fields.forEach { (name, value) -> `when`(document.get(name)).thenReturn(value) }
        }
    }
}
