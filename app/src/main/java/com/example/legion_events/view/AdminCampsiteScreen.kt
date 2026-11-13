/*
 * AdminCampsiteScreen.kt
 *
 * Purpose: S19 campsite details for an admin, with Remove listing and a confirmation (US 03.01.01).
 * Role: View in MVC; renders AdminCampsiteState and forwards input to AdminCampsiteController.
 * Outstanding issues: Remove image (US 03.03.01) is added by the admin images part.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.AdminCampsiteState

/**
 * Shows one campsite to an admin and lets them remove it.
 * @param state The campsite and the removal progress.
 * @param onBack Returns to the campsite list.
 * @param onRetry Reloads after a failure.
 * @param onRemove Opens the remove confirmation.
 * @param onConfirmRemove Removes the campsite.
 * @param onCancelRemove Closes the confirmation.
 */
@Composable
fun AdminCampsiteScreen(
    state: AdminCampsiteState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack, enabled = !state.removing)
                Text("Campsite details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            val listing = state.listing
            when {
                state.loading && listing == null -> AdminLoading("Loading campsite…")
                state.error != null -> {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                listing != null -> {
                    val campsite = listing.campsite
                    CampsiteCover(state.cover, campsite.name, Modifier.fillMaxWidth().height(180.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(campsite.name.ifBlank { "Unnamed campsite" }, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!listing.active) RemovedPill()
                    }
                    Text(campsite.locationName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Owner: ${state.ownerName}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("${campsite.maxGuests} guests", "Max occupancy", Modifier.weight(1f))
                        StatTile(shortPrice(campsite.pricePerNight), "Per night", Modifier.weight(1f))
                    }
                    if (campsite.description.isNotBlank()) Text(campsite.description)
                    if (campsite.amenities.isNotEmpty()) Section("Amenities", campsite.amenities.joinToString(" · "))
                    if (campsite.rules.isNotEmpty()) Section("Rules", campsite.rules.joinToString("\n"))
                    state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (listing.active) {
                        Button(
                            onClick = onRemove,
                            enabled = !state.removing,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Remove listing") }
                    } else {
                        Text("This listing was removed. Entrants can no longer find or open it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (state.confirmingRemove) {
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text("Remove this listing?") },
            text = { Text("The campsite disappears from search and its details can no longer be opened. The owner cannot list it again.") },
            confirmButton = {
                TextButton(onClick = onConfirmRemove, enabled = !state.removing) {
                    Text(if (state.removing) "Removing…" else "Remove")
                }
            },
            dismissButton = { TextButton(onClick = onCancelRemove, enabled = !state.removing) { Text("Cancel") } },
        )
    }
}

/**
 * A titled block of campsite text.
 * @param title The section title.
 * @param body The section text.
 */
@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Text(body)
    }
}
