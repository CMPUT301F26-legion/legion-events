/*
 * BookingProcessor.kt
 * Purpose: Coordinate booking confirmation, rejection cleanup, and replacement draws.
 * Role: Backend orchestration shared by the CLI, event listener, and recovery sweep.
 * Outstanding issues: None.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.Firestore

/** Counts completed state changes; already processed records never count again. */
internal data class BookingProcessingResult(val confirmed: Int, val released: Int, val replacements: Int) {
    override fun toString() = "Confirmed $confirmed booking(s), released $released rejection(s), drew $replacements replacement(s)."
}

/** All stages run even if one fails; the caller receives an aggregate error so a later run can retry. */
internal class BookingProcessor(firestore: Firestore) {
    private val confirmations = ReservationConfirmationWorker(firestore)
    private val rejections = ReservationRejectionWorker(firestore)
    private val replacements = OfferReplacementWorker(firestore)

    /** Releases rejected stays before checking new requests, then processes each decline once. */
    fun sweep(): BookingProcessingResult {
        val failures = mutableListOf<Exception>()
        fun attempt(work: () -> Int): Int = try { work() } catch (error: Exception) { failures += error; 0 }
        val released = attempt(rejections::processPending)
        val confirmed = attempt(confirmations::processPending)
        val drawn = attempt(replacements::processPending)
        if (failures.isNotEmpty()) throw IllegalStateException("Booking sweep had ${failures.size} failed stage(s).")
            .also { summary -> failures.forEach(summary::addSuppressed) }
        return BookingProcessingResult(confirmed, released, drawn)
    }

    /** Reads current reservation state, independent of the event's order or obsolete payload. */
    fun reservationChanged(id: String) {
        rejections.processRequest(id)
        confirmations.processRequest(id)
    }

    /** Only explicit declines can draw replacements; winning and acceptance events never redraw. */
    fun offerChanged(id: String) { replacements.processOffer(id) }
}
