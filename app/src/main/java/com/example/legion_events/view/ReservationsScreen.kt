package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.ReservationsState
import com.example.legion_events.data.ReservationEntry

/**
 * S10: shows the entrant's upcoming and ongoing confirmed reservations.
 * @param state Active bookings and request state from the controller.
 * @param onRefresh Reloads after a failure or a change elsewhere in the app.
 * @param onReservation Opens the selected booking by document ID.
 */
@Composable
fun ReservationsScreen(state: ReservationsState, onRefresh: () -> Unit, onReservation: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("My reservations", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
            }
            PrimaryTabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = {}, text = { Text("Active") })
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    state.loading -> item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Loading reservations…", Modifier.padding(top = 12.dp))
                        }
                    }
                    state.error != null -> item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRefresh, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                    }
                    state.entries.isEmpty() -> item {
                        Text("No active reservations", style = MaterialTheme.typography.titleMedium)
                        Text("Your upcoming and ongoing confirmed stays will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> items(state.entries, key = { it.reservation.id }) { entry ->
                        ReservationCard(entry) { onReservation(entry.reservation.id) }
                    }
                }
            }
        }
    }
}

/** Shows saved booking dates and price; tapping a card or its button opens the same details route. */
@Composable
private fun ReservationCard(entry: ReservationEntry, onClick: () -> Unit) {
    val reservation = entry.reservation
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Confirmed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(entry.campsiteName ?: "Campsite ${reservation.campsiteId}",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            entry.locationName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Check-in: ${reservationDateText(reservation.startDate)}")
            Text("Check-out: ${reservationDateText(reservation.endDate)}")
            Text("${reservation.guestCount} ${if (reservation.guestCount == 1) "guest" else "guests"} · ${shortPrice(reservation.totalPrice)} CAD total")
            Text("Reservation ${reservation.id}", style = MaterialTheme.typography.bodySmall)
            if (reservation.cancellationRequested) Text("Cancellation requested")
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("View reservation") }
        }
    }
}
