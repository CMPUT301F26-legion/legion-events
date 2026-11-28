/* ReservationRejectionWorkerTest.kt: rejection cleanup and safe reopening of pending claims. */
package com.example.legion_events.worker

import org.junit.Assert.*
import org.junit.Test

class ReservationRejectionWorkerTest : BookingWorkerFixture() {
    @Test fun ownerRejectingAPendingRequestReopensTheSameOfferAndAFreshAttemptCanConfirm() {
        seedRequest(fields = mapOf("status" to "rejected"))
        val worker = ReservationRejectionWorker(firestore)
        assertEquals(1, worker.processPending())
        assertNull(data("offers/draw_alice")["response"])
        assertEquals(expiry, data("offers/draw_alice")["expiresAt"])
        assertEquals("rejected", data("reservations/booking")["status"])
        assertEquals(0, worker.processPending())
        put("reservations/retry", requestFields())
        update("offers/draw_alice", mapOf("response" to "accept", "reservationId" to "retry", "respondedAt" to accepted))
        assertTrue(ReservationConfirmationWorker(firestore).processRequest("retry"))
    }
    @Test fun confirmedOwnerRejectionReleasesOnlyOwnedNightsAndPreservesTheUsedOffer() {
        seedRequest()
        assertTrue(ReservationConfirmationWorker(firestore).processRequest("booking"))
        update("reservations/booking", mapOf("status" to "rejected", "cancellationRequested" to true))
        val first = locks().first()
        update("bookedNights/${first.id}", mapOf("reservationId" to "someone-else"))
        val offer = data("offers/draw_alice")
        val worker = ReservationRejectionWorker(firestore)
        assertTrue(worker.processRequest("booking"))
        assertEquals(listOf(first.id), locks().map { it.id })
        assertEquals(offer, data("offers/draw_alice"))
        assertEquals(false, data("reservations/booking")["cancellationRequested"])
        val marker = data("reservationReleases/booking")
        assertFalse(worker.processRequest("booking"))
        assertEquals(marker, data("reservationReleases/booking"))
        assertEquals(1, firestore.collection("notifications").get().get().size())
    }
    @Test fun cleanupOfAnOldRejectedAttemptCannotReopenOrConsumeANewerClaim() {
        seedRequest(fields = mapOf("status" to "rejected"))
        put("reservations/new-attempt", requestFields())
        update("offers/draw_alice", mapOf("reservationId" to "new-attempt"))
        val offer = data("offers/draw_alice")
        assertTrue(ReservationRejectionWorker(firestore).processRequest("booking"))
        assertEquals(offer, data("offers/draw_alice"))
        assertTrue(ReservationConfirmationWorker(firestore).processRequest("new-attempt"))
    }
    @Test fun crossCampsiteLocksFailBeforeAnyPartialReleaseOrOfferChange() {
        seedRequest(fields = mapOf("status" to "rejected"))
        put("bookedNights/other_date", mapOf("reservationId" to "booking", "campsiteId" to "other"))
        assertThrows(IllegalStateException::class.java) { ReservationRejectionWorker(firestore).processPending() }
        assertTrue(exists("bookedNights/other_date"))
        assertEquals("accept", data("offers/draw_alice")["response"])
        assertFalse(exists("reservationReleases/booking"))
    }
}
