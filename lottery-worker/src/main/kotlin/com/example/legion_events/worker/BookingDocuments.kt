/*
 * BookingDocuments.kt
 * Purpose: Strict backend readers and UTC night keys for the PR 1 booking contract.
 * Role: Shared data helpers for trusted booking processors; no Android dependency.
 * Outstanding issues: None.
 */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import java.time.LocalDate
import java.time.ZoneOffset

/** Rejects blank identifiers and paths before constructing a Firestore document reference. */
internal fun bookingId(value: Any?): String = (value as? String)?.also {
    check(it.isNotBlank() && '/' !in it) { "Invalid booking document ID." }
} ?: error("Missing booking document ID.")

/** Reads a required single-document reference without inventing a fallback value. */
internal fun DocumentSnapshot.bookingId(field: String): String = bookingId(get(field))

/** Reads a server timestamp; malformed data must never authorize confirmation. */
internal fun DocumentSnapshot.bookingTime(field: String): Timestamp =
    get(field) as? Timestamp ?: error("Missing or invalid $field.")

/** Lists bounded, contiguous UTC nights, excluding checkout, with no device time-zone arithmetic. */
internal fun bookingNights(start: Timestamp, end: Timestamp): List<String> {
    check(start.nanos == 0 && end.nanos == 0 && start.seconds % 86400 == 0L && end.seconds % 86400 == 0L) {
        "Stay dates must be UTC midnight."
    }
    val count = (end.seconds - start.seconds) / 86400
    check(count in 1..366) { "Stay must contain 1 to 366 nights." }
    val first = LocalDate.ofEpochDay(start.seconds / 86400)
    return (0 until count).map { first.plusDays(it).toString() }
}

/** Owner-listed night keys must name real, four-digit-year calendar dates. */
internal fun validBookingDate(value: String): Boolean =
    value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
        runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)

/** Extracts UTC calendar day from an acceptance timestamp, for validating past-date submissions. */
internal fun Timestamp.bookingDaySeconds(): Long =
    LocalDate.ofInstant(java.time.Instant.ofEpochSecond(seconds, nanos.toLong()), ZoneOffset.UTC).toEpochDay() * 86400

/** Validates the draw's bounded, unique campsite list. */
internal fun DocumentSnapshot.bookingCampsites(): List<String> {
    val ids = get("campsiteIds") as? List<*> ?: error("Missing offered campsites.")
    check(ids.size in 1..8 && ids.distinct().size == ids.size) { "Invalid offered campsites." }
    return ids.map(::bookingId)
}

/** A stable Inbox payload committed with the corresponding reservation state transition. */
internal fun reservationNotice(userId: String, lotteryId: String, reservationId: String): Map<String, Any?> = mapOf(
    "toUserId" to userId, "fromUserId" to null, "lotteryId" to lotteryId,
    "reservationId" to reservationId, "type" to "reservation_confirmed",
    "title" to "Reservation confirmed", "body" to "Your campsite reservation is confirmed. View your booking details.",
    "read" to false, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
)
