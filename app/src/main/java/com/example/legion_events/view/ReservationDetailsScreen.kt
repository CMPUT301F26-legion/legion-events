/*
 * ReservationDetailsScreen.kt
 *
 * Purpose: Display an owned reservation's saved booking details on S10b.
 * Role: View in MVC; renders detail state and forwards Retry and navigation actions.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.ReservationDetailsState
import com.example.legion_events.model.Reservation
import com.google.firebase.Timestamp

/**
 * S10b: shows an owned reservation's saved details.
 * @param state Booking and request state supplied by the controller.
 * @param onBack Returns to the existing reservations list.
 * @param onRetry Reloads after a read failure.
 * @param onCampsite Opens the campsite through the shared router.
 * @param onConfirmation Opens S09 for this confirmed booking or completed cancellation.
 * @param onDownload Opens Android's Save dialog for the current receipt.
 * @param onCancel Requests the cancellation dialog.
 * @param onDismissCancellation Dismisses the dialog without submitting.
 * @param onConfirmCancellation Submits the reviewed cancellation request.
 * @param onRate Opens S10c for this completed stay.
 */
@Composable
fun ReservationDetailsScreen(state: ReservationDetailsState, onBack: () -> Unit, onRetry: () -> Unit,
    onCampsite: (String) -> Unit, onConfirmation: () -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onDismissCancellation: () -> Unit, onConfirmCancellation: () -> Unit, onRate: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text("Reservation details", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry, enabled = !state.loading && !state.exporting && !state.requestingCancellation) { Text("Refresh") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val entry = state.entry
                when {
                    state.loading -> item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Loading reservation…", Modifier.padding(top = 12.dp))
                        }
                    }
                    state.error != null || entry == null -> item {
                        Text(state.error ?: "This reservation is not available for this account.")
                        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                    }
                    else -> {
                        val reservation = entry.reservation
                        item {
                            Text(entry.campsiteName ?: "Campsite unavailable",
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            entry.locationName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        item {
                            Text("Reservation ${reservation.id}", style = MaterialTheme.typography.titleMedium)
                            Text("Status: ${reservationStatusText(reservation.status)}")
                            if (reservation.cancellationRequested) Text("Cancellation requested. Waiting for processing; your dates remain reserved.")
                            if (reservation.cancellationCompleted()) Text("Reservation cancelled. Your dates have been released.")
                            state.cancellationNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            Text("Check-in: ${reservationDateText(reservation.startDate)}")
                            Text("Check-out: ${reservationDateText(reservation.endDate)}")
                            Text("Checkout date is not an occupied night.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Text("Guests: ${reservation.guestCount}")
                            Text("Total: ${shortPrice(reservation.totalPrice)} CAD", style = MaterialTheme.typography.titleLarge)
                            Text(if (reservation.rulesAccepted) "Campsite rules accepted" else "Campsite rules not accepted")
                        }
                        item { OutlinedButton(onClick = { onCampsite(reservation.campsiteId) }) { Text("View campsite") } }
                        if (reservation.canRate(Timestamp.now())) {
                            item { OutlinedButton(onClick = onRate, modifier = Modifier.fillMaxWidth()) { Text("Rate your stay") } }
                        }
                        if (reservation.status == Reservation.STATUS_CONFIRMED || reservation.cancellationCompleted()) {
                            item {
                                OutlinedButton(onClick = onConfirmation, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (reservation.cancellationCompleted()) "View cancellation confirmation" else "View confirmation")
                                }
                                ReservationDownloadButton(state, onDownload)
                            }
                        }
                        if (reservation.canRequestCancellation(Timestamp.now()) || state.requestingCancellation) {
                            item {
                                OutlinedButton(onClick = onCancel, enabled = !state.requestingCancellation && !state.exporting,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.requestingCancellation) "Requesting cancellation…" else "Cancel reservation")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.confirmingCancellation) AlertDialog(
        onDismissRequest = onDismissCancellation,
        title = { Text("Cancel this reservation?") },
        text = { Text("This submits a cancellation request. Your reservation stays confirmed until processing completes.") },
        confirmButton = { TextButton(onClick = onConfirmCancellation) { Text("Request cancellation") } },
        dismissButton = { TextButton(onClick = onDismissCancellation) { Text("Keep reservation") } },
    )
}
