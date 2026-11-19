/*
 * ReservationConfirmationScreen.kt
 *
 * Purpose: Show S09's booking or cancellation confirmation and CSV (US 01.05.02/01.07.02).
 * Role: View in MVC; displays controller state and forwards navigation and save actions.
 * Outstanding issues: Booking creation must open S09 with its saved reservation ID.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.ReservationDetailsState
import com.example.legion_events.model.Reservation

/**
 * Shows confirmation only after loading an owned confirmed booking or completed cancellation.
 * @param state Saved booking and loading, failure, and export state.
 * @param onBack Returns to the previous destination.
 * @param onRetry Reloads an unavailable booking.
 * @param onDownload Opens Android's Save dialog for the confirmation CSV.
 * @param onReservation Opens the existing reservation details page.
 */
@Composable
fun ReservationConfirmationScreen(state: ReservationDetailsState, onBack: () -> Unit, onRetry: () -> Unit,
    onDownload: () -> Unit, onReservation: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text("Reservation confirmation", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }
            val entry = state.entry
            when {
                state.loading -> {
                    CircularProgressIndicator()
                    Text("Loading confirmation…")
                }
                state.error != null || entry == null -> {
                    Text(state.error ?: "This reservation is not available for this account.")
                    Button(onClick = onRetry) { Text("Retry") }
                }
                entry.reservation.status != Reservation.STATUS_CONFIRMED && !entry.reservation.cancellationCompleted() -> {
                    Text("This reservation is not confirmed.")
                    OutlinedButton(onClick = onReservation) { Text("View reservation") }
                }
                else -> {
                    val reservation = entry.reservation
                    Text(if (reservation.cancellationCompleted()) "Cancellation confirmed" else "Reservation confirmed", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(if (reservation.cancellationCompleted()) "Your reservation was cancelled and its dates released."
                        else "Your saved booking confirmation is available below.")
                    if (reservation.cancellationRequested) Text("Cancellation requested. Waiting for processing.")
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.campsiteName ?: "Campsite unavailable", style = MaterialTheme.typography.titleLarge)
                            entry.locationName?.let { Text(it) }
                            Text("Reservation ${reservation.id}")
                            Text("Check-in: ${reservationDateText(reservation.startDate)}")
                            Text("Check-out: ${reservationDateText(reservation.endDate)}")
                            Text("Guests: ${reservation.guestCount}")
                            Text("Total: ${shortPrice(reservation.totalPrice)} CAD")
                            if (reservation.cancellationCompleted()) reservation.updatedAt?.let { Text("Cancelled: ${reservationDateText(it)}") }
                        }
                    }
                    ReservationDownloadButton(state, onDownload)
                    OutlinedButton(onClick = onReservation, modifier = Modifier.fillMaxWidth()) { Text("View reservation") }
                }
            }
        }
    }
}

/** Shows the shared CSV action and save feedback without performing file or Firebase access. */
@Composable
internal fun ReservationDownloadButton(state: ReservationDetailsState, onDownload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDownload, enabled = !state.exporting && !state.requestingCancellation, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.exporting) "Saving CSV…" else if (state.entry?.reservation?.cancellationCompleted() == true)
                "Download cancellation CSV" else "Download confirmation CSV")
        }
        state.exportNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
