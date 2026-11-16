/*
 * ReservationCsvTest.kt
 *
 * Purpose: Verify confirmation receipt values, CSV escaping, and locale independence.
 * Role: Local JVM tests for the reservation CSV model helper using synthetic bookings.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Checks the exported file format without Firebase or Android file access. */
class ReservationCsvTest {
    private val reservation = Reservation("booking", "lottery", "offer", "pine", "alice", "owner",
        Timestamp(100, 0), Timestamp(200, 0), 2, 6550, true, "confirmed")

    @Test fun receiptContainsSavedBookingValuesAndOptionalListingLabels() {
        val csv = reservation.confirmationCsv("Pine Ridge", "Banff")
        assertEquals("\"reservationId\",\"campsiteId\",\"campsiteName\",\"locationName\",\"startDateUtc\"," +
            "\"endDateUtc\",\"guestCount\",\"totalPriceCad\",\"currency\",\"status\",\"rulesAccepted\"\r\n" +
            "\"booking\",\"pine\",\"Pine Ridge\",\"Banff\",\"1970-01-01T00:01:40.000Z\"," +
            "\"1970-01-01T00:03:20.000Z\",\"2\",\"65.50\",\"CAD\",\"confirmed\",\"true\"\r\n", csv)
        assertFalse(csv.contains("alice"))
        assertFalse(csv.contains("owner"))
    }

    @Test fun quotesCommasLineBreaksAndUnicodeStayInsideTheirCells() {
        val csv = reservation.copy(id = "booking,\"one\"").confirmationCsv("Pine, \"Ridge\"\r\n北", "Québec")
        assertTrue(csv.contains("\"booking,\"\"one\"\"\""))
        assertTrue(csv.contains("\"Pine, \"\"Ridge\"\"\r\n北\""))
        assertTrue(csv.contains("\"Québec\""))
    }

    @Test fun spreadsheetFormulaTextIsExportedAsText() {
        for (text in listOf("=1+1", "+1", "-1", "@SUM(1)", "  =1", "\tvalue")) {
            assertTrue(reservation.copy(id = text).confirmationCsv(text).contains("\"'$text\""))
        }
    }

    @Test fun utcDatesAndCadDecimalsDoNotDependOnThePhoneSettings() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Edmonton"))
            val csv = reservation.copy(totalPrice = Long.MAX_VALUE).confirmationCsv()
            assertTrue(csv.contains("\"1970-01-01T00:01:40.000Z\""))
            assertTrue(csv.contains("\"92233720368547758.07\""))
            assertTrue(csv.contains("\"pine\",\"\",\"\""))
            assertTrue(reservation.copy(totalPrice = 0).confirmationCsv().contains("\"0.00\""))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test fun cancelledRejectedAndUnconfirmedBookingsCannotProduceConfirmationReceipts() {
        for (status in listOf("cancelled", "rejected", "pending", "unknown")) {
            assertThrows(IllegalArgumentException::class.java) { reservation.copy(status = status).confirmationCsv() }
        }
    }
}
