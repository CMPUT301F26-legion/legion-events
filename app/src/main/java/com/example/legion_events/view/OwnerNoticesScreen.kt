/*
 * OwnerNoticesScreen.kt
 *
 * Purpose: S18 owner notices: send a one-way notice to a lottery's waitlist and see every
 *   notice sent (US 02.10.01, 02.10.02).
 * Role: View in MVC; renders OwnerNoticesState and forwards input to OwnerNoticesController.
 * Outstanding issues: Messaging individual booked entrants (US 02.12.01) is a later part.
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.OwnerNoticesState
import com.example.legion_events.model.MAX_NOTICE_LENGTH
import java.text.DateFormat

/**
 * Shows the notice form for the owner's lotteries and the list of sent notices.
 * @param state Lotteries, the notice being written, and sent notices.
 * @param onSelectLottery Chooses the lottery whose waitlist receives the notice.
 * @param onMessage Updates the notice text.
 * @param onSend Sends the notice.
 * @param onRetry Reloads after a failure.
 */
@Composable
fun OwnerNoticesScreen(
    state: OwnerNoticesState,
    onSelectLottery: (String) -> Unit,
    onMessage: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Notices", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Send a notice to everyone on a lottery's waiting list.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                state.loading && state.targets.isEmpty() && state.sent.isEmpty() -> item { Text("Loading your notices…") }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    item { NoticeForm(state, onSelectLottery, onMessage, onSend) }
                    item {
                        Text("Sent notices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp))
                        if (state.sent.isEmpty()) {
                            Text("You have not sent any notices yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(state.sent, key = { it.first.id }) { (notice, label) ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(notice.message)
                                val count = notice.recipientIds.size
                                val time = notice.createdAt?.let {
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.toDate())
                                } ?: "Sending…"
                                Text("$time · $count ${if (count == 1) "entrant" else "entrants"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lottery chips, the notice text field, and the Send button.
 * @param state Current form state.
 * @param onSelectLottery Chooses a lottery.
 * @param onMessage Updates the text.
 * @param onSend Sends the notice.
 */
@Composable
private fun NoticeForm(
    state: OwnerNoticesState,
    onSelectLottery: (String) -> Unit,
    onMessage: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.targets.isEmpty()) {
            Text("You have no lotteries yet. Create one in the Lotteries tab first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Text("Send to", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        state.targets.forEach { target ->
            FilterChip(
                selected = target.id == state.selectedLotteryId,
                onClick = { onSelectLottery(target.id) },
                label = { Text("${target.label} · ${target.entrantCount} waiting") },
            )
        }
        OutlinedTextField(
            value = state.message,
            onValueChange = onMessage,
            label = { Text("Notice") },
            supportingText = { Text(state.messageError ?: "${state.message.length} / $MAX_NOTICE_LENGTH") },
            isError = state.messageError != null,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSend, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.sending) "Sending…" else "Send notice")
        }
        state.result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
