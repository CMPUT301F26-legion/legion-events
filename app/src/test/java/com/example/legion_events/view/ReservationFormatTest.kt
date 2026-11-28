/* ReservationFormatTest.kt: stay dates keep their UTC calendar day in any device time zone. */
package com.example.legion_events.view

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ReservationFormatTest {
    /** 2026-11-30T00:00:00Z, the saved check-in of a stay booked for Nov 30. */
    private val nov30 = Timestamp(1_795_996_800L, 0)

    @Test fun stayDatesShowTheirUtcDayInWesternAndEasternTimeZones() {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CANADA)
            for (id in listOf("America/Edmonton", "UTC", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                assertEquals("Nov 30, 2026", reservationDateText(nov30))
            }
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
    }

    @Test fun eventTimesUseTheDeviceTimeZone() {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CANADA)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Edmonton"))
            // 00:00 UTC on Nov 30 is still the evening of Nov 29 in Alberta.
            assertEquals("Nov 29, 2026", reservationEventDateText(nov30))
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
    }
}
