/*
 * BookingOfferScreen.kt
 *
 * Purpose: Show owned offers, their status, and a confirmed decline action.
 * Role: View in MVC; renders controller state and forwards user actions.
 * Outstanding issues: Offer acceptance and campsite/date selection are separate work.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.BookingOfferState
import com.example.legion_events.controller.BookingOffersState
import com.example.legion_events.model.BookingOffer
import com.google.firebase.Timestamp
import java.text.DateFormat

/**
 * Lists the authenticated user's offers independently of the Part 5 inbox.
 * @param state List read and failure state.
 * @param onBack Returns to the previous screen.
 * @param onRetry Reloads owned offers.
 * @param onOpen Opens a selected offer using only its document ID.
 */
@Composable
fun BookingOffersScreen(
    state: BookingOffersState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Lottery offers", style = MaterialTheme.typography.headlineMedium)
            }
            when {
                state.loading -> item { CircularProgressIndicator(); Text("Loading offers…") }
                state.error != null -> item {
                    Text(state.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    if (state.offers.isEmpty()) item { Text("You have no lottery offers yet.") }
                    items(state.offers, key = { it.id }) { offer ->
                        Card(onClick = { onOpen(offer.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Campsite lottery offer", style = MaterialTheme.typography.titleMedium)
                                Text(offerStatus(offer))
                                offer.createdAt?.let { Text("Offered: ${offerDate(it)}") }
                                Text("View offer")
                            }
                        }
                    }
                    item { TextButton(onClick = onRetry) { Text("Refresh offers") } }
                }
            }
        }
    }
}

/**
 * Displays one offer and a confirmation dialog for the Part 6 decline action.
 * Reservation acceptance and campsite/date selection are separate work.
 * @param state Current offer and action state.
 * @param onBack Returns to the previous list or inbox entry.
 * @param onRetry Reloads the current offer after a read failure or uncertain action outcome.
 * @param onDecline Requests confirmation.
 * @param onConfirmDecline Submits the confirmed decline.
 * @param onCancelDecline Dismisses confirmation without writing.
 */
@Composable
fun BookingOfferScreen(
    state: BookingOfferState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDecline: () -> Unit,
    onConfirmDecline: () -> Unit,
    onCancelDecline: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onClick = onBack, enabled = !state.declining) { Text("Back") }
                Text("Your campsite offer", style = MaterialTheme.typography.headlineMedium)
            }
            val offer = state.offer
            when {
                state.loading -> item { CircularProgressIndicator(); Text("Loading offer…") }
                state.error != null || offer == null -> item {
                    Text(state.error ?: "This offer is no longer available.")
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(offerStatus(offer, state.expired), style = MaterialTheme.typography.titleLarge)
                        offer.expiresAt?.let { Text("Response deadline: ${offerDate(it)}") }
                        if (offer.status == BookingOffer.STATUS_DECLINED) {
                            Text("You declined this offer.")
                        } else if (state.canDecline) {
                            Text("You were selected in a campsite lottery. You can decline this offer if you do not want it.")
                        }
                        state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.declining) {
                            CircularProgressIndicator()
                            Text("Declining offer…")
                        }
                        if (offer.status == BookingOffer.STATUS_OPEN && !state.expired) {
                            Button(onClick = onDecline, enabled = state.canDecline) { Text("Decline offer") }
                        }
                        TextButton(onClick = onRetry, enabled = !state.declining) { Text("Reload offer") }
                    }
                }
            }
        }
    }
    if (state.confirming) {
        AlertDialog(
            onDismissRequest = onCancelDecline,
            title = { Text("Decline this offer?") },
            text = { Text("You will give up this opportunity to book. This cannot be undone here.") },
            confirmButton = { TextButton(onClick = onConfirmDecline) { Text("Confirm decline") } },
            dismissButton = { TextButton(onClick = onCancelDecline) { Text("Keep offer") } },
        )
    }
}

/** Formats a response timestamp using the device's locale and time zone. */
private fun offerDate(time: Timestamp): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(time.toDate())

/** Returns a readable status; an elapsed response deadline also counts as expired for display. */
private fun offerStatus(offer: BookingOffer, expired: Boolean = false): String = when {
    expired || (offer.status == BookingOffer.STATUS_OPEN && offer.expiresAt?.let { it <= Timestamp.now() } == true) -> "Expired"
    offer.status == BookingOffer.STATUS_OPEN -> "Open offer"
    offer.status == BookingOffer.STATUS_DECLINED -> "Declined"
    offer.status == BookingOffer.STATUS_USED -> "Used"
    offer.status == BookingOffer.STATUS_EXPIRED -> "Expired"
    else -> "Unavailable"
}
