/*
 * AdminCampsitesScreen.kt
 *
 * Purpose: S19 Campsites tab: every campsite with a search field, leading to its details (US 03.04.01).
 *   Also holds the header and status pill shared by the S19 screens.
 * Role: View in MVC; renders AdminCampsitesState and forwards input to AdminCampsitesController.
 * Outstanding issues: Rows show no thumbnail, so the list loads without reading every photo.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.AdminCampsitesState

/**
 * Lists every campsite for an admin, with search.
 * @param state The campsites and the search text.
 * @param onBack Returns to the profile screen.
 * @param onQuery Updates the search text.
 * @param onRetry Reloads after a failure.
 * @param onCampsite Opens one campsite's admin details.
 */
@Composable
fun AdminCampsitesScreen(
    state: AdminCampsitesState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onCampsite: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AdminHeader("Campsite listings", onBack) }
            item {
                OutlinedTextField(value = state.query, onValueChange = onQuery, label = { Text("Search campsites") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            when {
                state.loading && state.rows.isEmpty() -> item { AdminLoading("Loading campsites…") }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                state.shown.isEmpty() -> item {
                    Text(if (state.rows.isEmpty()) "No campsites have been listed yet." else "No campsites match your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(state.shown, key = { it.listing.campsite.id }) { row ->
                    val campsite = row.listing.campsite
                    AdminCard(Modifier.clickable { onCampsite(campsite.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(campsite.name.ifBlank { "Unnamed campsite" }, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (!row.listing.active) RemovedPill()
                        }
                        Text(campsite.locationName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${campsite.maxGuests} guests · ${shortPrice(campsite.pricePerNight)}/night",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Owner: ${row.ownerName}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * The S19 title row: back arrow, "Admin dashboard" label, and the tab's title.
 * @param title The tab's title.
 * @param onBack Returns to the previous screen.
 */
@Composable
internal fun AdminHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackArrow(onBack)
        Column {
            Text("Admin dashboard", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A progress spinner with a caption.
 * @param text What is loading.
 */
@Composable
internal fun AdminLoading(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(text, Modifier.padding(top = 12.dp))
    }
}

/**
 * An outlined card used for each S19 row.
 * @param modifier Adds click handling.
 * @param content The card's lines.
 */
@Composable
internal fun AdminCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/**
 * A small rounded label, such as a role or a removed status.
 * @param text The label.
 * @param container The background colour.
 * @param content The text colour.
 */
@Composable
internal fun AdminPill(text: String, container: Color, content: Color) {
    Text(text, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(container, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** The pill shown on a removed campsite or profile. */
@Composable
internal fun RemovedPill() =
    AdminPill("Removed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
