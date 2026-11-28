/* BookingSelectionTest.kt: real gaps, occupied nights, checkout boundaries, UTC dates, and CAD totals. */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class BookingSelectionTest {
    private val start = 1_800_000_000L.floorDiv(86_400) * 86_400
    private val at = Timestamp(start, 0)
    private fun millis(day: Int) = (start + day * 86_400) * 1000
    private fun choice(occupied: Set<String> = emptySet()) = BookingCampsite(Campsite(
        "pine", "owner", "Pine", "Alberta", 4, 1555,
        availableNights = bookingNightKeys(at, Timestamp(start + 4 * 86_400, 0))), occupied)

    @Test fun checkoutsCanFollowTheLastFreeNightButCannotCrossALockedNight() {
        val locked = bookingNightKeys(Timestamp(start + 2 * 86_400, 0), Timestamp(start + 3 * 86_400, 0)).single()
        val site = choice(setOf(locked))
        assertEquals(setOf(millis(1), millis(2)), bookingCheckouts(site, millis(0)))
        assertEquals(setOf(millis(4)), bookingCheckouts(site, millis(3)))
        assertTrue(bookingCheckouts(site, millis(2)).isEmpty())
        assertNull(BookingSelection("pine", millis(0), millis(2), 2).problem(site, at))
        assertNotNull(BookingSelection("pine", millis(0), millis(3), 2).problem(site, at))
    }

    @Test fun pastCheckInsAreExcludedButTodayRemainsAvailable() {
        assertEquals(setOf(millis(1), millis(2), millis(3)), bookingCheckIns(choice(), Timestamp(start + 86_400 + 1, 0)))
        assertNotNull(BookingSelection("pine", millis(0), millis(1)).problem(choice(), Timestamp(start + 86_400, 0)))
        assertNull(BookingSelection("pine", millis(1), millis(2)).problem(choice(), Timestamp(start + 86_400, 0)))
    }

    @Test fun wholeDatesCapacityAndIntegerTotalsAreRequired() {
        assertEquals(4665L, BookingSelection("pine", millis(0), millis(3), 4).total(choice().campsite))
        assertNull(BookingSelection("pine", millis(0) + 1, millis(3)).total(choice().campsite))
        assertNull(BookingSelection("pine", millis(0), millis(0)).total(choice().campsite))
        assertNotNull(BookingSelection("pine", millis(0), millis(3), 5).problem(choice(), at))
        assertNotNull(BookingSelection("another", millis(0), millis(3)).problem(choice(), at))
    }

    @Test fun daylightSavingAndDeviceTimeZonesDoNotShiftCampingDates() {
        val original = TimeZone.getDefault()
        try {
            val expected = bookingCheckouts(choice(), millis(0))
            for (zone in listOf("America/Edmonton", "Pacific/Kiritimati", "Pacific/Honolulu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(expected, bookingCheckouts(choice(), millis(0)))
                assertNull(BookingSelection("pine", millis(0), millis(4)).problem(choice(), at))
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun maximumStayHas366CheckoutChoicesAndDoesNotIncludeTheCheckoutNight() {
        val site = choice().let { it.copy(campsite = it.campsite.copy(
            availableNights = bookingNightKeys(at, Timestamp(start + 366 * 86_400L, 0)))) }
        assertEquals(366, bookingCheckouts(site, millis(0)).size)
        assertNull(BookingSelection("pine", millis(0), millis(366)).problem(site, at))
        assertNotNull(BookingSelection("pine", millis(0), millis(367)).problem(site, at))
    }
}
