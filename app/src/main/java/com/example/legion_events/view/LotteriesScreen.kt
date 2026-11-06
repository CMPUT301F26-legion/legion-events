/*
 * LotteriesScreen.kt
 *
 * Purpose: Give entrants a home for lottery entries and booking offers under the Lotteries tab.
 * Role: View in MVC; renders MyLotteriesState, forwards leave actions to MyLotteriesController
 *   and offer navigation to AppNavigation.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.MyLotteriesState
import com.example.legion_events.data.LotteryEntry
import com.example.legion_events.model.WaitlistEntrant

/**
 * S06: the entrant's lottery entries (US 01.02.02) and the Part 6 offer flow under the Lotteries tab.
 * @param entries The entrant's entries and leave progress.
 * @param canLeave Whether an entry's waitlist can still be left.
 * @param onLeave Opens the leave confirmation for a lottery.
 * @param onCancelLeave Closes the confirmation.
 * @param onConfirmLeave Leaves the confirmed lottery.
 * @param onRetry Reloads the entries.
 * @param onOffers Opens the authenticated user's booking offers.
 */
@Composable
fun LotteriesScreen(
    entries: MyLotteriesState = MyLotteriesState(loading = false),
    canLeave: (LotteryEntry) -> Boolean = { false },
    onLeave: (String) -> Unit = {},
    onCancelLeave: () -> Unit = {},
    onConfirmLeave: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOffers: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text("My lotteries", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your entries and opportunities to book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Booking offers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Selected in a lottery?", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Review your offers and respond before their deadlines.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onOffers, modifier = Modifier.fillMaxWidth()) { Text("View offers") }
                    }
                }
            }
            item {
                Text("Lottery entries", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                entries.actionError?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                when {
                    entries.loading && entries.entries.isEmpty() ->
                        Text("Loading your entries…", modifier = Modifier.padding(top = 8.dp))
                    entries.error != null -> {
                        Text(entries.error, modifier = Modifier.padding(top = 8.dp))
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                    entries.entries.isEmpty() -> Text(
                        "You have not joined a lottery yet. Open a campsite to join its lottery.",
                        modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entries.error == null) {
                items(entries.entries, key = { it.lottery.id }) { entry ->
                    EntryCard(entry, canLeave(entry), !entries.leaving, onLeave)
                }
            }
        }
    }
    if (entries.confirmingLeave != null) {
        AlertDialog(
            onDismissRequest = onCancelLeave,
            title = { Text("Leave waiting list?") },
            text = { Text("You will not be in the draw. You can join again while registration is open.") },
            confirmButton = {
                TextButton(onClick = onConfirmLeave, enabled = !entries.leaving) {
                    Text(if (entries.leaving) "Leaving…" else "Leave")
                }
            },
            dismissButton = { TextButton(onClick = onCancelLeave, enabled = !entries.leaving) { Text("Stay") } },
        )
    }
}

/**
 * One lottery entry with its campsites, dates, status, and Leave button.
 * @param entry The lottery and the entrant's status.
 * @param canLeave Whether Leave is shown.
 * @param enabled Whether Leave can be pressed now.
 * @param onLeave Opens the leave confirmation.
 */
@Composable
private fun EntryCard(entry: LotteryEntry, canLeave: Boolean, enabled: Boolean, onLeave: (String) -> Unit) {
    val lottery = entry.lottery
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.campsiteNames.joinToString().ifEmpty { "Campsite lottery" },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Registration: ${periodText(lottery.registrationStartAt, lottery.registrationEndAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(statusText(entry.status), fontWeight = FontWeight.SemiBold)
            if (canLeave) {
                OutlinedButton(onClick = { onLeave(lottery.id) }, enabled = enabled) { Text("Leave waiting list") }
            }
        }
    }
}

/**
 * Describes a stored waitlist status for the entrant.
 * @param status The stored status, or null.
 * @return A short label.
 */
private fun statusText(status: String?): String = when (status) {
    WaitlistEntrant.STATUS_JOINED -> "On the waiting list"
    WaitlistEntrant.STATUS_WITHDRAWN -> "Left the waiting list"
    "won" -> "Selected in the draw"
    "lost" -> "Not selected this time"
    else -> "Not joined"
}
