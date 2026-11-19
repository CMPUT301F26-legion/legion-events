/*
 * ReservationCsv.kt
 *
 * Purpose: Format booking and completed cancellation receipts (US 01.05.02/01.07.02).
 * Role: Model helper in MVC; serializes booking values without database or file access.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Creates a two-row receipt from the saved booking, with UTC dates and an exact CAD amount.
 * Only confirmed reservations can produce a confirmation; past confirmed stays are supported.
 * @param campsiteName Optional current listing name; it does not replace the campsite ID.
 * @param locationName Optional current listing area.
 * @return UTF-8-ready CSV text with quoted cells and CRLF row endings.
 */
fun Reservation.confirmationCsv(campsiteName: String? = null, locationName: String? = null): String {
    require(status == Reservation.STATUS_CONFIRMED) { "Only confirmed reservations have a confirmation receipt." }
    return receiptCsv(campsiteName, locationName)
}

/**
 * Exports cancellation only after the worker has committed the terminal state and server timestamp.
 * @param campsiteName Optional current listing name.
 * @param locationName Optional current listing area.
 * @return A cancellation receipt with the same booking columns and an additional cancellation time.
 */
fun Reservation.cancellationCsv(campsiteName: String? = null, locationName: String? = null): String {
    require(cancellationCompleted() && updatedAt != null) { "Cancellation has not completed." }
    return receiptCsv(campsiteName, locationName, updatedAt.csvUtc())
}

/** Shares the escaping and saved booking columns between confirmation and cancellation receipts. */
private fun Reservation.receiptCsv(campsiteName: String?, locationName: String?, cancelledAt: String? = null): String {
    val headings = listOf("reservationId", "campsiteId", "campsiteName", "locationName", "startDateUtc",
        "endDateUtc", "guestCount", "totalPriceCad", "currency", "status", "rulesAccepted") +
        if (cancelledAt != null) listOf("cancelledAtUtc") else emptyList()
    val values = listOf(id, campsiteId, campsiteName.orEmpty(), locationName.orEmpty(), startDate.csvUtc(),
        endDate.csvUtc(), guestCount.toString(), BigDecimal.valueOf(totalPrice, 2).toPlainString(),
        "CAD", status, rulesAccepted.toString()) + if (cancelledAt != null) listOf(cancelledAt) else emptyList()
    return listOf(headings, values).joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { it.csvCell() }
    }
}

/** Formats an instant in UTC to millisecond precision, independently of device settings. */
private fun Timestamp.csvUtc(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(toDate())

/** Quotes separators and line breaks, and keeps spreadsheet apps from treating text as a formula. */
private fun String.csvCell(): String {
    val formula = trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
        firstOrNull() in listOf('\t', '\r', '\n')
    val text = if (formula) "'$this" else this
    return "\"${text.replace("\"", "\"\"")}\""
}
