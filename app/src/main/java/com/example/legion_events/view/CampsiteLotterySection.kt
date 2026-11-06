/*
 * CampsiteLotterySection.kt
 *
 * Purpose: S04 lottery section: join an open lottery's waitlist from campsite details
 *   (US 01.01.06, 01.02.01), with the S06b joined confirmation.
 * Role: View in MVC; renders CampsiteLotteryState and forwards actions to CampsiteLotteryController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.CampsiteLotteryState
import com.example.legion_events.data.LotteryEntry
import com.example.legion_events.model.WaitlistEntrant

/**
 * Shows each open lottery for the campsite with a Join button, or the entrant's joined status.
 * @param state Lotteries and join progress from the controller.
 * @param onJoin Joins the given lottery's waitlist.
 * @param onRetry Reloads the lotteries after a failure.
 * @param onMyLotteries Opens S06, where the entrant can leave a waitlist.
 */
@Composable
fun CampsiteLotterySection(
    state: CampsiteLotteryState,
    onJoin: (String) -> Unit,
    onRetry: () -> Unit,
    onMyLotteries: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lottery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when {
            state.loading && state.lotteries.isEmpty() -> Text("Checking for open lotteries…")
            state.error != null -> {
                Text(state.error)
                TextButton(onClick = onRetry) { Text("Retry lotteries") }
            }
            state.lotteries.isEmpty() -> Text("No lottery is open for this campsite right now.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> state.lotteries.forEach { entry ->
                LotteryJoinCard(entry, state.joining == entry.lottery.id,
                    state.joining != null, state.joinedLotteryId == entry.lottery.id, onJoin, onMyLotteries)
            }
        }
        state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * One open lottery with its dates, spaces left, and Join button or joined status.
 * @param entry The lottery and the entrant's status in it.
 * @param joining Whether this lottery is being joined.
 * @param busy Whether any join is running.
 * @param justJoined Whether the entrant joined it during this visit.
 * @param onJoin Joins this lottery.
 * @param onMyLotteries Opens S06.
 */
@Composable
private fun LotteryJoinCard(
    entry: LotteryEntry,
    joining: Boolean,
    busy: Boolean,
    justJoined: Boolean,
    onJoin: (String) -> Unit,
    onMyLotteries: () -> Unit,
) {
    val lottery = entry.lottery
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Registration: ${periodText(lottery.registrationStartAt, lottery.registrationEndAt)}")
            Text("Stay: ${periodText(lottery.bookingStartAt, lottery.bookingEndAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val spaces = (lottery.waitlistLimit - lottery.entrantCount).coerceAtLeast(0)
            Text("${lottery.winnerCount} winners · $spaces of ${lottery.waitlistLimit} waitlist spaces left",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.status == WaitlistEntrant.STATUS_JOINED) {
                Text(if (justJoined) "You joined the waiting list. Good luck in the draw!" else "You are on the waiting list.",
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onMyLotteries) { Text("View my lotteries") }
            } else {
                Button(
                    onClick = { onJoin(lottery.id) },
                    enabled = !busy && spaces > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (joining) "Joining…" else if (spaces > 0) "Join lottery" else "Waitlist full") }
            }
        }
    }
}
