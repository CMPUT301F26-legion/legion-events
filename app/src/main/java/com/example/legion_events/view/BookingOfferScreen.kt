/*
 * BookingOfferScreen.kt
 *
 * Purpose: Show owned offers, their status, and a confirmed decline action.
 * Role: View in MVC; renders controller state and forwards user actions.
 * Outstanding issues: Offer acceptance and campsite/date selection are separate work.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.R
import com.example.legion_events.controller.BookingOfferState
import com.example.legion_events.controller.BookingOffersState
import com.example.legion_events.model.BookingOffer
import com.google.firebase.Timestamp
import java.text.DateFormat

/**
 * Lists the authenticated user's offers from the Lotteries tab or a notification.
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
        Column(Modifier.fillMaxSize()) {
            OfferHeader("Booking offers", "View and respond to your offers.", onBack)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    state.loading -> item { OfferLoading("Loading offers…") }
                    state.error != null -> item {
                        OfferMessage("Offers unavailable", state.error)
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Retry") }
                    }
                    else -> {
                        if (state.offers.isEmpty()) item {
                            OfferMessage("You have no lottery offers yet.",
                                "When you are selected in a lottery, your offer will appear here.")
                        }
                        items(state.offers, key = { it.id }) { offer ->
                            BookingOfferCard(offer) { onOpen(offer.id) }
                        }
                        item { TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Refresh offers") } }
                    }
                }
            }
        }
    }
}

/**
 * Displays one offer with a bottom decline action and a separate confirmation dialog.
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
    val offer = state.offer
    val status = offer?.let { offerStatus(it, state.expired) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            OfferHeader("Booking offer", "Your lottery result", onBack, backEnabled = !state.declining)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    state.loading -> item { OfferLoading("Loading offer…") }
                    state.error != null || offer == null -> item {
                        OfferMessage("Offer unavailable", state.error ?: "This offer is no longer available.")
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Retry") }
                    }
                    else -> {
                        item {
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Icon(painterResource(R.drawable.ic_nav_lotteries), contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                    OfferStatusLabel(checkNotNull(status))
                                    Text(if (status == "Open offer") "You were selected!" else "Campsite lottery offer",
                                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(when (status) {
                                        "Open offer" -> "You have an opportunity to book through this lottery. Review the response deadline before deciding."
                                        "Declined" -> "You declined this offer."
                                        "Used" -> "This offer has already been used to book."
                                        else -> "The response deadline has passed. This offer is no longer available."
                                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    offer.createdAt?.let { Text("Offered: ${offerDate(it)}", style = MaterialTheme.typography.bodyMedium) }
                                    offer.expiresAt?.let {
                                        Text("Response deadline: ${offerDate(it)}", style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                        state.actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    }
                }
            }
            if (!state.loading && state.error == null && offer != null) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.declining || status == "Open offer") {
                            OutlinedButton(
                                onClick = onDecline, enabled = state.canDecline,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                if (state.declining) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(if (state.declining) "Declining offer…" else "Decline offer",
                                    modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                        TextButton(onClick = onRetry, enabled = !state.declining, modifier = Modifier.fillMaxWidth()) {
                            Text("Reload offer")
                        }
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
            confirmButton = {
                TextButton(onClick = onConfirmDecline) { Text("Confirm decline", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onCancelDecline) { Text("Keep offer") } },
        )
    }
}

/**
 * Shows an offer using the same outlined card and text styles as campsite listings.
 * @param offer One owned offer supplied by the repository.
 * @param onOpen Opens the selected document.
 */
@Composable
private fun BookingOfferCard(offer: BookingOffer, onOpen: () -> Unit) {
    OutlinedCard(
        onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OfferStatusLabel(offerStatus(offer))
            Text("Campsite lottery offer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            offer.createdAt?.let { Text("Offered: ${offerDate(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            offer.expiresAt?.let { Text("Respond by: ${offerDate(it)}", style = MaterialTheme.typography.bodyMedium) }
            Text("View offer", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Keeps the shared back arrow and screen title above scrollable offer content.
 * @param title Screen heading.
 * @param subtitle Short description of this step.
 * @param onBack Returns to the caller's previous route.
 * @param backEnabled Whether a pending decline should prevent the header's Back action.
 */
@Composable
private fun OfferHeader(title: String, subtitle: String, onBack: () -> Unit, backEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BackArrow(onBack, enabled = backEnabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shows loading progress without shifting the screen header or bottom navigation. */
@Composable
private fun OfferLoading(message: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(message)
    }
}

/** Presents an empty or unavailable state using the same card treatment as loaded offers. */
@Composable
private fun OfferMessage(title: String, message: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Displays a readable offer status with the shared pill style and theme colors. */
@Composable
private fun OfferStatusLabel(status: String) {
    val colors = MaterialTheme.colorScheme
    val open = status == "Open offer"
    Text(status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
        color = if (open) colors.onPrimaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.background(if (open) colors.primaryContainer else colors.surfaceVariant,
            RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
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
