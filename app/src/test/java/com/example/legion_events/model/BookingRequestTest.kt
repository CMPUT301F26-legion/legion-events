/* BookingRequestTest.kt: verifies UTC stay boundaries, gaps, totals, and reviewed policy acceptance. */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class BookingRequestTest {
    private val start = Timestamp(1_815_696_000, 0) // 2027-07-16 UTC
    private val end = Timestamp(start.seconds + 3 * 86_400, 0)
    private val campsite = Campsite("pine", "owner", "Pine", "Banff", 4, 1500,
        rules = listOf("Quiet after 10 pm"), cancellationPolicy = "No refunds",
        availableNights = bookingNightKeys(start, end))
    private fun request() = BookingRequest("attempt", "draw_entrant", "pine", start, end, 2, 4500,
        BookingTerms.from(campsite), true)

    @Test fun stayExcludesCheckoutAndIsIndependentOfDaylightSavingAndDeviceTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Edmonton"))
            val western = bookingNightKeys(start, end)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals(western, bookingNightKeys(start, end))
            assertEquals(listOf("2027-07-16", "2027-07-17", "2027-07-18"), western)
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun invalidOrUnboundedStaysAreRejectedBeforeEnumerating() {
        for ((a, b) in listOf(start to start, end to start, Timestamp(start.seconds + 1, 0) to end,
            Timestamp(start.seconds, 1) to end, start to Timestamp(start.seconds + 367 * 86_400, 0))) {
            assertThrows(IllegalArgumentException::class.java) { bookingNightKeys(a, b) }
        }
        assertEquals(366, bookingNightKeys(start, Timestamp(start.seconds + 366 * 86_400, 0)).size)
    }
    @Test fun listedNightsMinusLocksKeepGapsVisibleWithoutHoldingAnyNights() {
        val choice = BookingCampsite(campsite, setOf("2027-07-17", "2030-01-01"))
        assertEquals(setOf("2027-07-16", "2027-07-18"), choice.availableNightKeys)
        assertEquals(3, campsite.availableNights.size)
    }
    @Test fun requestMustMatchReviewedTermsPriceAndGuestCapacity() {
        request().validateFor(campsite)
        val invalid = listOf(request().copy(rulesAccepted = false), request().copy(totalPrice = 4501),
            request().copy(guestCount = 0), request().copy(guestCount = 5), request().copy(id = "other/id"),
            request().copy(terms = BookingTerms(emptyList(), "No refunds")),
            request().copy(terms = BookingTerms(campsite.rules, "Changed")))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it.validateFor(campsite) } }
        request().copy(totalPrice = 0).validateFor(campsite.copy(pricePerNight = 0))
    }
    @Test fun bookingSubmissionWindowDoesNotRestrictTheCampingDatesAndHasAnExclusiveDeadline() {
        val lottery = Lottery("draw", "owner", listOf("pine"), Timestamp(10, 0), Timestamp(20, 0),
            Timestamp(30, 0), Timestamp(40, 0), 1)
        val offer = BookingOffer("draw_entrant", "draw", "entrant", expiresAt = Timestamp(40, 0))
        val options = BookingOptions(offer, lottery, emptyList())
        assertFalse(options.canAccept(Timestamp(29, 0)))
        assertTrue(options.canAccept(Timestamp(30, 0)))
        assertFalse(options.canAccept(Timestamp(40, 0)))
        assertFalse(options.copy(offer = offer.copy(status = BookingOffer.STATUS_ACCEPTED)).canAccept(Timestamp(31, 0)))
        request().validateFor(campsite) // Future stay is independent of the submission window.
    }
    @Test fun lockIdSerializesCompetingBookingsForTheSameCampsiteNight() {
        assertEquals(BookedNight("pine", "a", "2027-07-16").id, BookedNight("pine", "b", "2027-07-16").id)
        assertNotEquals(BookedNight("pine", "a", "2027-07-16").id, BookedNight("lake", "a", "2027-07-16").id)
        assertThrows(IllegalArgumentException::class.java) { BookedNight("pine", "a", "2027-02-30") }
    }
}
