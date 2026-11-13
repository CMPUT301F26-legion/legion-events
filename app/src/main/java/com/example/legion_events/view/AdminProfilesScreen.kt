/*
 * AdminProfilesScreen.kt
 *
 * Purpose: S19 Profiles tab: every profile with a search field, Remove profile, and Remove owner
 *   (US 03.05.01, 03.02.01, 03.07.01).
 * Role: View in MVC; renders AdminProfilesState and forwards input to AdminProfilesController.
 * Outstanding issues: Remove picture (US 03.03.01) is added by the admin images part.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.AdminProfileRow
import com.example.legion_events.controller.AdminProfilesState
import com.example.legion_events.controller.displayName

/**
 * Lists every profile for an admin, with search and removal.
 * @param state The profiles, search text, and removal progress.
 * @param onBack Returns to the profile screen.
 * @param onQuery Updates the search text.
 * @param onRetry Reloads after a failure.
 * @param onRemoveProfile Asks to remove a profile.
 * @param onRemoveOwner Asks to remove an owner and their campsites.
 * @param onConfirmRemove Saves the confirmed removal.
 * @param onCancelRemove Closes the confirmation.
 */
@Composable
fun AdminProfilesScreen(
    state: AdminProfilesState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onRemoveProfile: (String) -> Unit,
    onRemoveOwner: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AdminHeader("Profiles", onBack) }
            item {
                OutlinedTextField(value = state.query, onValueChange = onQuery, label = { Text("Search profiles") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                state.result?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            when {
                state.loading && state.rows.isEmpty() -> item { AdminLoading("Loading profiles…") }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                state.shown.isEmpty() -> item {
                    Text(if (state.rows.isEmpty()) "No profiles yet." else "No profiles match your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(state.shown, key = { it.profile.uid }) { row ->
                    ProfileRow(row, enabled = !state.removing, onRemoveProfile, onRemoveOwner)
                }
            }
        }
    }
    state.confirming?.let { pending ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(if (pending.withCampsites) "Remove ${pending.name} as an owner?" else "Remove ${pending.name}?") },
            text = {
                Text(if (pending.withCampsites) {
                    "Their account is disabled and all of their campsites are removed from search."
                } else {
                    "Their account is disabled and they can no longer use the app."
                })
            },
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
 * One profile card with its role, contact details, and removal buttons.
 * @param row The profile and its campsite count.
 * @param enabled Whether the buttons accept taps.
 * @param onRemoveProfile Asks to remove the profile.
 * @param onRemoveOwner Asks to remove the owner and their campsites.
 */
@Composable
private fun ProfileRow(
    row: AdminProfileRow,
    enabled: Boolean,
    onRemoveProfile: (String) -> Unit,
    onRemoveOwner: (String) -> Unit,
) {
    val profile = row.profile
    val colors = MaterialTheme.colorScheme
    AdminCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(displayName(profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (row.admin) AdminPill(row.roleLabel, colors.errorContainer, colors.onErrorContainer)
            else AdminPill(row.roleLabel, colors.primaryContainer, colors.onPrimaryContainer)
            if (profile.disabled) RemovedPill()
        }
        val contact = listOf(profile.email, profile.phone).filter(String::isNotBlank).joinToString(" · ")
        Text(contact.ifEmpty { "No contact details" }, style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        if (row.activeCampsites > 0) {
            Text("${row.activeCampsites} active ${if (row.activeCampsites == 1) "campsite" else "campsites"}",
                style = MaterialTheme.typography.bodySmall)
        }
        if (!row.admin && !profile.disabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (row.activeCampsites > 0) {
                    OutlinedButton(onClick = { onRemoveOwner(profile.uid) }, enabled = enabled) { Text("Remove owner") }
                }
                OutlinedButton(onClick = { onRemoveProfile(profile.uid) }, enabled = enabled) { Text("Remove profile") }
            }
        }
    }
}
