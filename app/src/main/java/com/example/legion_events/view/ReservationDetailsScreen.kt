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

/**
 * S10b: shows an owned reservation's saved details.
 * @param state Booking and request state supplied by the controller.
 * @param onBack Returns to the existing reservations list.
 * @param onRetry Reloads after a read failure.
 * @param onCampsite Opens the campsite through the shared router.
 */
@Composable
fun ReservationDetailsScreen(state: ReservationDetailsState, onBack: () -> Unit, onRetry: () -> Unit,
    onCampsite: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text("Reservation details", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                            Text(entry.campsiteName ?: "Campsite ${reservation.campsiteId}",
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            entry.locationName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        item {
                            Text("Reservation ${reservation.id}", style = MaterialTheme.typography.titleMedium)
                            Text("Status: ${reservation.status}")
                            if (reservation.cancellationRequested) Text("Cancellation requested")
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
                    }
                }
            }
        }
    }
}
