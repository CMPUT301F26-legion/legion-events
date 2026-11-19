/*
 * OwnerReservationModelTest.kt
 *
 * Purpose: Verify booked nights, owner-visible and cancelled statuses, the rejection notice, and calendar layout
 *   used by S17 (US 02.06.01, 02.07.03, 02.08.01).
 * Role: Local JVM tests for model helpers; no Firebase access.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the pure date and status rules behind the owner reservations screen. */
class OwnerReservationModelTest {
    /** 2027-07-04T00:00:00Z in seconds. */
    private val july4 = 1_814_659_200L
    private val day = 24 * 60 * 60L

    @Test fun bookedNightsExcludeCheckoutAndUseUtcDays() {
        assertEquals(listOf("2027-07-04", "2027-07-05", "2027-07-06"), booking(july4, july4 + 3 * day).bookedNights())
        // An 11 am checkout on the 7th still frees the 7th, and an afternoon check-in holds its own night.
        assertEquals(listOf("2027-07-04", "2027-07-05", "2027-07-06"),
            booking(july4 + 15 * 3600, july4 + 3 * day + 11 * 3600).bookedNights())
    }

    @Test fun aSameDayStayHoldsOneNightAndStaysCrossMonths() {
        assertEquals(listOf("2027-07-04"), booking(july4 + 3600, july4 + 7200).bookedNights())
        assertEquals(listOf("2027-06-30", "2027-07-01"), booking(july4 - 4 * day, july4 - 2 * day).bookedNights())
    }

    @Test fun onlyCancelledAndRejectedBookingsCountAsCancelled() {
        assertTrue(booking(status = Reservation.STATUS_CANCELLED).isCancelled())
        assertTrue(booking(status = Reservation.STATUS_REJECTED).isCancelled())
        for (status in listOf(Reservation.STATUS_CONFIRMED, "pending", "unknown")) {
            assertFalse(booking(status = status).isCancelled())
        }
    }

    @Test fun ownersSeeUpcomingRequestedAndConfirmedBookingsButEntrantsOnlyConfirmed() {
        val now = Timestamp(july4, 0)
        val requested = booking(july4 + day, july4 + 2 * day, Reservation.STATUS_REQUESTED)
        assertTrue(requested.isUpcomingForOwner(now))
        assertFalse(requested.isActive(now))
        assertTrue(booking(july4 + day, july4 + 2 * day).isUpcomingForOwner(now))
        // Checkout ends the stay, and a request that was never confirmed is not a past stay.
        val finishedRequest = booking(july4 - 2 * day, july4, Reservation.STATUS_REQUESTED)
        assertFalse(finishedRequest.isUpcomingForOwner(now))
        assertFalse(finishedRequest.isPast(now))
        for (status in listOf(Reservation.STATUS_CANCELLED, Reservation.STATUS_REJECTED, "pending")) {
            assertFalse(booking(july4 + day, july4 + 2 * day, status).isUpcomingForOwner(now))
        }
    }

    @Test fun ownersCanAcceptOnlyUpcomingRequestsWithoutACancellation() {
        val now = Timestamp(july4, 0)
        assertTrue(booking(july4 + day, july4 + 2 * day, Reservation.STATUS_REQUESTED).canOwnerAccept(now))
        assertFalse(booking(july4 + day, july4 + 2 * day).canOwnerAccept(now))
        assertFalse(booking(july4 - 2 * day, july4, Reservation.STATUS_REQUESTED).canOwnerAccept(now))
        assertFalse(booking(july4 + day, july4 + 2 * day, Reservation.STATUS_REQUESTED)
            .copy(cancellationRequested = true).canOwnerAccept(now))
    }

    @Test fun acceptanceNoticeNamesTheCampsiteAndStay() {
        val stay = booking(july4, july4 + 3 * day)
        assertEquals("The owner confirmed your reservation at Two Jack Lakeside for Jul 4, 2027 to Jul 7, 2027.",
            acceptanceNotice(stay, "Two Jack Lakeside"))
        assertEquals("The owner confirmed your reservation for Jul 4, 2027 to Jul 7, 2027.", acceptanceNotice(stay, " "))
    }

    @Test fun rejectionNoticeNamesTheCampsiteStayAndOptionalReason() {
        val stay = booking(july4, july4 + 3 * day)
        assertEquals("The owner rejected your reservation at Two Jack Lakeside for Jul 4, 2027 to Jul 7, 2027. " +
            "Reason: Fire ban", rejectionNotice(stay, "Two Jack Lakeside", "Fire ban"))
        assertEquals("The owner rejected your reservation for Jul 4, 2027 to Jul 7, 2027.",
            rejectionNotice(stay, null, null))
        assertEquals("The owner rejected your reservation for Jul 4, 2027 to Jul 7, 2027.",
            rejectionNotice(stay, " ", " "))
    }

    @Test fun rejectionNoticeStaysWithinTheNoticeLimit() {
        val notice = rejectionNotice(booking(), "x".repeat(2000), "y".repeat(Reservation.MAX_REJECTION_REASON_LENGTH))
        assertEquals(MAX_NOTICE_LENGTH, notice.length)
    }

    @Test fun calendarStartsOnSundayWithBlanksBeforeTheFirst() {
        // July 1, 2027 is a Thursday: four blanks (Sun to Wed), then 31 days.
        val cells = calendarCells("2027-07")
        assertEquals(4, cells.takeWhile { it == null }.size)
        assertEquals("2027-07-01", cells[4])
        assertEquals("2027-07-31", cells.last())
        assertEquals(35, cells.size)
        assertEquals(28, calendarCells("2027-02").count { it != null })
        assertEquals(29, calendarCells("2028-02").count { it != null })
    }

    @Test fun monthsShiftAcrossYearsAndAreNamed() {
        assertEquals("2028-01", shiftMonth("2027-12", 1))
        assertEquals("2026-12", shiftMonth("2027-01", -1))
        assertEquals("2027-07", monthOf(Timestamp(july4, 0)))
        assertEquals("July 2027", monthTitle("2027-07"))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidMonthKeysAreRejected() {
        calendarCells("2027-13")
    }

    /**
     * Builds a booking with the given dates and status.
     * @param start Check-in in epoch seconds.
     * @param end Checkout in epoch seconds.
     * @param status Stored status.
     * @return The booking.
     */
    private fun booking(start: Long = july4, end: Long = july4 + day, status: String = Reservation.STATUS_CONFIRMED) =
        Reservation("booking", "lottery", "offer", "lake", "entrant", "owner",
            Timestamp(start, 0), Timestamp(end, 0), 2, 7600, true, status)
}
