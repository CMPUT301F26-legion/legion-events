/* ReservationConfirmationWorkerTest.kt: atomic booking outcomes, conflicts, rollback, and concurrent retries. */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReservationConfirmationWorkerTest : BookingWorkerFixture() {
    @Test fun confirmsExplicitAcceptanceAndCommitsAllLocksOfferAndNoticeTogetherOnce() {
        seedRequest()
        val original = data("reservations/booking")
        val worker = ReservationConfirmationWorker(firestore)
        assertEquals(1, worker.processPending())
        val saved = data("reservations/booking")
        assertEquals("confirmed", saved["status"])
        original.filterKeys { it !in listOf("status", "updatedAt") }.forEach { (key, value) -> assertEquals(value, saved[key]) }
        assertEquals("used", data("offers/draw_alice")["status"])
        assertEquals("booking", data("offers/draw_alice")["reservationId"])
        assertEquals(2, locks().size)
        assertEquals(bookingNights(start, end).map { "pine_$it" }.toSet(), locks().map { it.id }.toSet())
        assertTrue(locks().all { it.getString("reservationId") == "booking" && it.getTimestamp("updatedAt") == null })
        val notice = data("notifications/reservation_confirmed_booking")
        assertEquals("reservation_confirmed", notice["type"])
        assertEquals("alice", notice["toUserId"])
        assertEquals("booking", notice["reservationId"])
        assertEquals(false, notice["read"])
        assertEquals(saved["updatedAt"], notice["createdAt"])
        assertEquals(saved["updatedAt"], data("offers/draw_alice")["updatedAt"])
        assertEquals(0, worker.processPending())
        assertFalse(worker.processRequest("booking"))
        assertEquals(saved, data("reservations/booking"))
        assertEquals(notice, data("notifications/reservation_confirmed_booking"))
    }
    @Test fun winningOrAnUnclaimedStandaloneRequestNeverAutomaticallyBooksAnything() {
        seedRequest()
        update("offers/draw_alice", mapOf("response" to null, "reservationId" to null))
        assertEquals(0, ReservationConfirmationWorker(firestore).processPending())
        assertEquals("requested", data("reservations/booking")["status"])
        assertTrue(locks().isEmpty())
        assertFalse(exists("notifications/reservation_confirmed_booking"))
        firestore.document("reservations/booking").delete().get()
        assertEquals(0, ReservationConfirmationWorker(firestore).processPending())
    }
    @Test fun dateConflictRejectsOnlyThisAttemptAndRetainsTheUnconsumedOfferForFreshDates() {
        seedRequest()
        val date = bookingNights(start, end).first()
        val prior = mapOf("campsiteId" to "pine", "date" to date, "reservationId" to "other-booking")
        put("bookedNights/pine_$date", prior)
        val worker = ReservationConfirmationWorker(firestore)
        assertEquals(0, worker.processPending())
        assertEquals("rejected", data("reservations/booking")["status"])
        assertEquals(prior, data("bookedNights/pine_$date"))
        val reopened = data("offers/draw_alice")
        assertEquals("pending", reopened["status"])
        assertNull(reopened["response"])
        assertFalse(reopened.containsKey("reservationId"))
        assertEquals(expiry, reopened["expiresAt"])
        assertFalse(exists("notifications/reservation_confirmed_booking"))
        val nextStart = end
        val nextEnd = Timestamp.ofTimeSecondsAndNanos(end.seconds + 86400, 0)
        update("campsites/pine", mapOf("availableNights" to bookingNights(start, nextEnd)))
        put("reservations/retry", requestFields() + mapOf("startDate" to nextStart, "endDate" to nextEnd, "totalPrice" to 1500))
        update("offers/draw_alice", mapOf("response" to "accept", "reservationId" to "retry", "respondedAt" to accepted))
        assertTrue(worker.processRequest("retry"))
        assertEquals("used", data("offers/draw_alice")["status"])
        assertEquals("rejected", data("reservations/booking")["status"])
        assertEquals(prior, data("bookedNights/pine_$date"))
    }
    @Test fun aTimelyAcceptanceStillConfirmsWhenTheWorkerRunsAfterTheOriginalDeadline() {
        seedRequest()
        val expired = Timestamp.ofTimeSecondsAndNanos(accepted.seconds + 1, 0)
        update("lotteries/draw", mapOf("bookingEndAt" to expired))
        update("offers/draw_alice", mapOf("expiresAt" to expired))
        assertTrue(Timestamp.now() > expired)
        assertTrue(ReservationConfirmationWorker(firestore).processRequest("booking"))
    }
    @Test fun invalidEligibilityPoliciesPricesCapacityAndListedDatesNeverConfirm() {
        val invalid = listOf(
            "request" to mapOf("rulesAccepted" to false), "request" to mapOf("guestCount" to 5),
            "request" to mapOf("totalPrice" to 1), "request" to mapOf("cancellationRequested" to true),
            "campsite" to mapOf("active" to false), "campsite" to mapOf("ownerId" to "other"),
            "campsite" to mapOf("availableNights" to emptyList<String>()), "campsite" to mapOf("pricePerNight" to 1600),
            "profile" to mapOf("disabled" to true), "lottery" to mapOf("bookingStartAt" to expiry),
        )
        for ((kind, changes) in invalid) {
            seedRequest()
            val path = when (kind) { "request" -> "reservations/booking"; "campsite" -> "campsites/pine"; "profile" -> "users/alice"; else -> "lotteries/draw" }
            update(path, changes)
            assertFalse(ReservationConfirmationWorker(firestore).processRequest("booking"))
            assertEquals("rejected", data("reservations/booking")["status"])
            assertNull(data("offers/draw_alice")["response"])
            assertTrue(locks().isEmpty())
        }
    }
    @Test fun conflictingNotificationRollsBackEveryBookingWriteAndOtherRequestsStillRun() {
        seedRequest()
        seedRequest("valid", "bob")
        put("notifications/reservation_confirmed_booking", mapOf("existing" to true))
        assertThrows(IllegalStateException::class.java) { ReservationConfirmationWorker(firestore).processPending() }
        assertEquals("requested", data("reservations/booking")["status"])
        assertEquals("pending", data("offers/draw_alice")["status"])
        assertEquals(mapOf("existing" to true), data("notifications/reservation_confirmed_booking"))
        assertEquals("confirmed", data("reservations/valid")["status"])
        assertTrue(locks().all { it.getString("reservationId") == "valid" })
    }
    @Test fun malformedRecordsReportFailureWithoutBlockingAnotherValidRequest() {
        seedRequest("broken", fields = mapOf("startDate" to Timestamp.ofTimeSecondsAndNanos(start.seconds, 1000)))
        seedRequest("valid", "bob")
        assertThrows(IllegalStateException::class.java) { ReservationConfirmationWorker(firestore).processPending() }
        assertEquals("requested", data("reservations/broken")["status"])
        assertEquals("confirmed", data("reservations/valid")["status"])
    }
    @Test fun competingWorkersAndEntrantsCanCommitOnlyOneReservationForOverlappingNights() {
        seedRequest()
        seedRequest("second", "bob")
        val ready = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<Int> { ready.await(); ReservationConfirmationWorker(firestore).processPending() } }
            ready.countDown()
            assertEquals(1, calls.sumOf { it.get(45, TimeUnit.SECONDS) })
        }
        val requests = firestore.collection("reservations").get().get().documents
        assertEquals(1, requests.count { it.getString("status") == "confirmed" })
        assertEquals(1, requests.count { it.getString("status") == "rejected" })
        assertEquals(1, locks().map { it.getString("reservationId") }.toSet().size)
        assertEquals(1, firestore.collection("notifications").get().get().size())
    }
    @Test fun maximumStayIsAtomicAndCheckoutNightRemainsUnlocked() {
        seedRequest()
        val checkout = Timestamp.ofTimeSecondsAndNanos(start.seconds + 366 * 86400, 0)
        update("campsites/pine", mapOf("availableNights" to bookingNights(start, checkout)))
        update("reservations/booking", mapOf("endDate" to checkout, "totalPrice" to 366 * 1500))
        assertTrue(ReservationConfirmationWorker(firestore).processRequest("booking"))
        assertEquals(366, locks().size)
        val checkoutDate = java.time.LocalDate.ofEpochDay(checkout.seconds / 86400).toString()
        assertFalse(exists("bookedNights/pine_$checkoutDate"))
    }
}
