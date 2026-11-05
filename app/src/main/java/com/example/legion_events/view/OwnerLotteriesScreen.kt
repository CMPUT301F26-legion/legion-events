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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.OwnerLotteriesState
import com.example.legion_events.controller.OwnerLotteryItem

/**
 * The owner's Lotteries tab: every lottery they created, and the way into S15 and S16.
 * @param state The owner's lotteries and request state from the controller.
 * @param onRetry Reloads after a failed load.
 * @param onLotteryClick Opens S16 for a lottery ID.
 * @param onCreateLottery Opens a blank S15 form.
 */
@Composable
fun OwnerLotteriesScreen(
    state: OwnerLotteriesState,
    onRetry: () -> Unit,
    onLotteryClick: (String) -> Unit,
    onCreateLottery: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom padding keeps the last card clear of the Create button.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("My Lotteries", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Draws for campsites you own", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    state.loading -> item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Loading your lotteries…", Modifier.padding(top = 12.dp))
                        }
                    }
                    state.error != null -> item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                    state.items.isEmpty() -> item {
                        Text("You have no lotteries yet. Tap Create Lottery to start one.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> items(state.items, key = { it.lottery.id }) { item ->
                        LotteryCard(item) { onLotteryClick(item.lottery.id) }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onCreateLottery,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                text = { Text("Create Lottery") },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
            )
        }
    }
}

/**
 * One lottery card on the Lotteries tab.
 * @param item The lottery, its name, and its state.
 * @param onClick Opens S16.
 */
@Composable
private fun LotteryCard(item: OwnerLotteryItem, onClick: () -> Unit) {
    val lottery = item.lottery
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Registration " + periodText(lottery.registrationStartAt, lottery.registrationEndAt),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${lottery.entrantCount} joined · ${lottery.winnerCount} winner spots",
                    style = MaterialTheme.typography.bodySmall)
                LotteryBadgeChip(item.badge)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
