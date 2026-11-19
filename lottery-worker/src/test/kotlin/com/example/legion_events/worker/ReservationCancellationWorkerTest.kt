/*
 * ReservationCancellationWorkerTest.kt
 *
 * Purpose: Verify atomic cancellation, night release, notices, retries, and concurrent processing.
 * Role: Worker integration tests against isolated Firestore emulator demo projects.
 * Outstanding issues: None.
 */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Verifies saved Firestore outcomes using demo namespaces and synthetic booking records. */
class ReservationCancellationWorkerTest {
    private lateinit var firestore: Firestore
    private lateinit var worker: ReservationCancellationWorker

    /** Opens a fresh demo namespace when the local emulator is configured. */
    @Before fun connect() {
        val host = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
        assumeTrue("Set FIRESTORE_EMULATOR_HOST to run worker integration tests.", host.isNotBlank())
        firestore = FirestoreOptions.newBuilder().setProjectId("demo-cancel-${UUID.randomUUID().toString().take(12)}")
            .setEmulatorHost(host).build().service
        worker = ReservationCancellationWorker(firestore)
    }

    /** Closes the client without changing other test namespaces. */
    @After fun close() { if (::firestore.isInitialized) firestore.close() }

    /** Cancellation, released nights, and exactly one notice commit together; retries preserve their times. */
    @Test fun completedCancellationReleasesOnlyOwnedNightsAndKeepsTheSavedBooking() {
        seed("booking")
        night("pine_2027-05-14", "booking")
        night("pine_2027-05-15", "booking")
        night("pine_2027-05-16", "someone-else")
        firestore.document("offers/offer").create(mapOf("status" to "used")).get()
        val before = firestore.document("reservations/booking").get().get().data!!
        assertEquals(1, worker.processPending())
        val saved = firestore.document("reservations/booking").get().get()
        assertEquals("cancelled", saved.getString("status"))
        assertEquals(false, saved.getBoolean("cancellationRequested"))
        for ((key, value) in before.filterKeys { it !in listOf("status", "cancellationRequested", "updatedAt") }) {
            assertEquals(value, saved.get(key))
        }
        assertFalse(firestore.document("bookedNights/pine_2027-05-14").get().get().exists())
        assertFalse(firestore.document("bookedNights/pine_2027-05-15").get().get().exists())
        assertTrue(firestore.document("bookedNights/pine_2027-05-16").get().get().exists())
        assertEquals("used", firestore.document("offers/offer").get().get().getString("status"))
        val notice = firestore.document("notifications/reservation_cancelled_booking").get().get()
        assertEquals("reservation_cancelled", notice.getString("type"))
        assertEquals("booking", notice.getString("reservationId"))
        assertEquals("alice", notice.getString("toUserId"))
        assertEquals(false, notice.getBoolean("read"))
        assertEquals(saved.getTimestamp("updatedAt"), notice.getTimestamp("createdAt"))
        assertEquals(0, worker.processPending())
        assertEquals(saved.data, firestore.document("reservations/booking").get().get().data)
        assertEquals(notice.data, firestore.document("notifications/reservation_cancelled_booking").get().get().data)
    }

    /** A late worker still honours a committed request; unrequested and terminal bookings are untouched. */
    @Test fun onlyConfirmedRequestsAreProcessedEvenWhenTheStayHasAlreadyEnded() {
        seed("late-request")
        seed("unrequested", requested = false)
        seed("rejected", status = "rejected")
        seed("already-cancelled", status = "cancelled")
        assertEquals(1, worker.processPending())
        assertEquals("confirmed", firestore.document("reservations/unrequested").get().get().getString("status"))
        assertEquals(1, firestore.collection("notifications").get().get().size())
    }

    /** A conflicting confirmation cannot leave the booking cancelled with its nights still occupied. */
    @Test fun notificationConflictRollsBackTheWholeCancellationAndOtherRequestsStillComplete() {
        seed("conflict")
        seed("valid")
        night("pine_2027-05-14", "conflict")
        firestore.document("notifications/reservation_cancelled_conflict").create(mapOf("existing" to true)).get()
        assertThrows(IllegalStateException::class.java) { worker.processPending() }
        assertEquals("confirmed", firestore.document("reservations/conflict").get().get().getString("status"))
        assertEquals(true, firestore.document("reservations/conflict").get().get().getBoolean("cancellationRequested"))
        assertTrue(firestore.document("bookedNights/pine_2027-05-14").get().get().exists())
        assertEquals("cancelled", firestore.document("reservations/valid").get().get().getString("status"))
    }

    /** Two workers may discover the same request but commit only one cancellation and one notice. */
    @Test fun concurrentWorkersCompleteARequestOnce() {
        seed("booking")
        night("pine_2027-05-14", "booking")
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val runs = (1..2).map { executor.submit<Int> { start.await(); ReservationCancellationWorker(firestore).processPending() } }
            start.countDown()
            assertEquals(1, runs.sumOf { it.get(30, TimeUnit.SECONDS) })
            assertEquals(1, firestore.collection("notifications").get().get().size())
        } finally { executor.shutdownNow() }
    }

    /** Inconsistent ownership fails before any occupied night is deleted. */
    @Test fun aNightFromAnotherCampsiteDoesNotPartiallyReleaseTheBooking() {
        seed("booking")
        night("pine_2027-05-14", "booking")
        night("other_2027-05-14", "booking", "other")
        assertThrows(IllegalStateException::class.java) { worker.processPending() }
        assertEquals(2, firestore.collection("bookedNights").get().get().size())
        assertEquals("confirmed", firestore.document("reservations/booking").get().get().getString("status"))
        assertTrue(firestore.collection("notifications").get().get().isEmpty)
    }

    /** Oversized data is rejected rather than freeing a partial list; the supported boundary is atomic. */
    @Test fun transactionWriteLimitIsCheckedBeforeReleasingAnyNight() {
        seed("booking")
        val batch = firestore.batch()
        repeat(499) { index -> batch.create(firestore.document("bookedNights/night-$index"),
            mapOf("campsiteId" to "pine", "reservationId" to "booking", "date" to "2027-05-14")) }
        batch.commit().get()
        assertThrows(IllegalStateException::class.java) { worker.processPending() }
        assertEquals(499, firestore.collection("bookedNights").get().get().size())
        firestore.document("bookedNights/night-498").delete().get()
        assertEquals(1, worker.processPending())
        assertTrue(firestore.collection("bookedNights").get().get().isEmpty)
    }

    /** Creates a complete synthetic booking whose original stay is in the past. */
    private fun seed(id: String, status: String = "confirmed", requested: Boolean = true) {
        firestore.document("reservations/$id").create(mapOf(
            "userId" to "alice", "ownerId" to "owner", "lotteryId" to "lottery", "offerId" to "offer", "campsiteId" to "pine",
            "startDate" to Timestamp.parseTimestamp("2026-05-14T12:00:00Z"), "endDate" to Timestamp.parseTimestamp("2026-05-16T12:00:00Z"),
            "guestCount" to 2, "totalPrice" to 6550, "rulesAccepted" to true, "status" to status,
            "cancellationRequested" to requested, "createdAt" to Timestamp.ofTimeSecondsAndNanos(1, 0),
            "updatedAt" to Timestamp.ofTimeSecondsAndNanos(2, 0),
        )).get()
    }

    /** Creates an occupied night with its booking owner; dates are display metadata, not deletion criteria. */
    private fun night(id: String, reservationId: String, campsiteId: String = "pine") {
        firestore.document("bookedNights/$id").create(mapOf("campsiteId" to campsiteId,
            "reservationId" to reservationId, "date" to id.substringAfterLast('_'))).get()
    }
}
