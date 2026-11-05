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
import com.example.legion_events.controller.OwnerCampsiteItem
import com.example.legion_events.controller.OwnerDashboardState
import com.example.legion_events.model.Photo

/**
 * S12: lists the campsites the signed-in user owns, with their lottery states.
 * @param state The owner's campsites and request state from the controller.
 * @param onBack Returns to the profile screen.
 * @param onRetry Reloads after a failed load.
 * @param onCampsiteClick Opens S14 for a campsite ID.
 * @param onAddCampsite Opens a blank S13 form.
 */
@Composable
fun OwnerDashboardScreen(
    state: OwnerDashboardState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCampsiteClick: (String) -> Unit,
    onAddCampsite: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom padding keeps the last card clear of the Add Campsite button.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BackArrow(onBack)
                        Column {
                            Text("My Campsites", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Listings you own", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                when {
                    state.loading -> item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Loading your campsites…", Modifier.padding(top = 12.dp))
                        }
                    }
                    state.error != null -> item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                    else -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatTile(state.campsites.size.toString(), "Campsites", Modifier.weight(1f))
                                StatTile(if (state.lotteriesUnavailable) "–" else state.openLotteries.toString(),
                                    "Open lotteries", Modifier.weight(1f))
                            }
                            if (state.lotteriesUnavailable) {
                                Text("Lottery status is unavailable right now.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        if (state.campsites.isEmpty()) item {
                            Text("You have no campsites yet. Tap Add Campsite to list your first one.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(state.campsites, key = { it.campsite.id }) { item ->
                            OwnerCampsiteCard(item, item.campsite.photoId?.let(state.covers::get)) { onCampsiteClick(item.campsite.id) }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onAddCampsite,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                text = { Text("Add Campsite") },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
            )
        }
    }
}

/**
 * One campsite card on S12.
 * @param item The campsite and its lottery badge.
 * @param cover Its cover photo, or null for a placeholder.
 * @param onClick Opens the campsite.
 */
@Composable
private fun OwnerCampsiteCard(item: OwnerCampsiteItem, cover: Photo?, onClick: () -> Unit) {
    val site = item.campsite
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CampsiteCover(cover, "${site.name} cover photo", Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(site.name.ifBlank { "Untitled campsite" }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(site.locationName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${site.maxGuests} guests · ${shortPrice(site.pricePerNight)}/night", style = MaterialTheme.typography.bodySmall)
                item.badge?.let { LotteryBadgeChip(it) }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
