/*
 * LotteryManagementScreen.kt
 *
 * Purpose: S16: one lottery's status, counts, and waiting list (US 02.04.06, 02.09.01).
 * Role: View in MVC; renders LotteryManagementState.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.LotteryBadge
import com.example.legion_events.controller.LotteryManagementState
import com.example.legion_events.controller.ManagementTab
import com.example.legion_events.controller.WaitlistRow
import com.example.legion_events.model.Lottery

/**
 * S16: one lottery's status, counts, and waiting list (US 02.04.06, 02.05.01, 02.09.01).
 * @param state The lottery, its waiting list, and request state from the controller.
 * @param onBack Returns to the Lotteries tab.
 * @param onRetry Reloads after a failed load.
 * @param onEdit Opens S15 for this lottery; only enabled while editing is allowed.
 * @param onTab Switches between the waiting list and winners.
 */
@Composable
fun LotteryManagementScreen(
    state: LotteryManagementState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onTab: (ManagementTab) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BackArrow(onBack)
                    Text(state.title.ifEmpty { "Lottery" }, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onEdit, enabled = state.lottery != null && state.editBlockedReason == null) {
                        Text("Edit")
                    }
                }
            }
            val lottery = state.lottery
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                lottery != null -> {
                    item { StatusCard(lottery, state.badge, state.editBlockedReason) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(lottery.entrantCount.toString(), "Joined", Modifier.weight(1f))
                            StatTile(lottery.winnerCount.toString(), "Winner spots", Modifier.weight(1f))
                            StatTile(lottery.waitlistLimit.toString(), "Cap", Modifier.weight(1f))
                        }
                    }
                    item {
                        TabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent) {
                            Tab(state.tab == ManagementTab.WAITLIST, { onTab(ManagementTab.WAITLIST) },
                                text = { Text("Waiting list (${state.waitlist.size})") })
                            Tab(state.tab == ManagementTab.WINNERS, { onTab(ManagementTab.WINNERS) },
                                text = { Text("Winners") })
                        }
                    }
                    when (state.tab) {
                        ManagementTab.WAITLIST -> when {
                            state.waitlistError != null -> item {
                                Text(state.waitlistError, color = MaterialTheme.colorScheme.error)
                                Button(onClick = onRetry) { Text("Retry") }
                            }
                            state.waitlist.isEmpty() -> item {
                                Text("Nobody has joined yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> items(state.waitlist, key = { it.position }) { WaitlistRowItem(it) }
                        }
                        ManagementTab.WINNERS -> item {
                            Text(
                                if (lottery.winnersDrawn) "Winners have been drawn. Their booking status will appear here."
                                else "Winners are drawn automatically when registration closes.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The status stepper with the registration and booking periods.
 * @param lottery The lottery.
 * @param badge Its state right now.
 * @param editBlockedReason Why Edit is disabled, or null.
 */
@Composable
private fun StatusCard(lottery: Lottery, badge: LotteryBadge?, editBlockedReason: String?) {
    // Steps reached: none before registration opens, then Open, Closed, Drawn.
    val reached = when (badge) {
        LotteryBadge.OPEN -> 1
        LotteryBadge.CLOSED -> 2
        LotteryBadge.DRAWN -> 3
        else -> 0
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            badge?.let { LotteryBadgeChip(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("Open", "Closed", "Drawn").forEachIndexed { index, label ->
                    if (index > 0) HorizontalDivider(Modifier.weight(1f).padding(horizontal = 4.dp))
                    Step(label, done = index < reached)
                }
            }
            Text("Registration " + periodText(lottery.registrationStartAt, lottery.registrationEndAt) +
                " · Draw runs automatically", style = MaterialTheme.typography.bodySmall)
            Text("Booking " + periodText(lottery.bookingStartAt, lottery.bookingEndAt), style = MaterialTheme.typography.bodySmall)
            editBlockedReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One step of the status stepper.
 * @param label The step name.
 * @param done Whether the lottery has reached it.
 */
@Composable
private fun Step(label: String, done: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val colors = MaterialTheme.colorScheme
        Box(
            Modifier.size(20.dp).background(if (done) colors.primary else colors.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", color = colors.onPrimary, style = MaterialTheme.typography.labelSmall)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/**
 * One waiting-list row: place, entrant, and join date.
 * @param row The row.
 */
@Composable
private fun WaitlistRowItem(row: WaitlistRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(row.position.toString(), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(row.label, style = MaterialTheme.typography.bodyLarge)
            Text(joinedText(row.joinedAt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
