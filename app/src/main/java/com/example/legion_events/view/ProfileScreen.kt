package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.ProfileState

/**
 * S11: edits contact details, the notification opt-out, and profile deletion.
 * @param state Profile values and request state from the controller.
 * @param onNameChange Sends name edits to the controller.
 * @param onEmailChange Sends email edits to the controller.
 * @param onPhoneChange Sends phone edits to the controller.
 * @param onSave Saves the contact fields.
 * @param onNotificationsChange Saves the notification choice.
 * @param onPolicy Opens the app policy and guidelines.
 * @param onDelete Opens the delete confirmation.
 * @param onConfirmDelete Deletes the profile.
 * @param onCancelDelete Closes the delete confirmation.
 * @param onRetry Reloads the profile after a failed load.
 */
@Composable
fun ProfileScreen(
    state: ProfileState,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSave: () -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onPolicy: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Profile & settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            when {
                state.loading -> CircularProgressIndicator()
                state.loadError != null -> {
                    Text(state.loadError, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    Text("Identified by this device. No username or password needed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileField("Name", state.name, state.nameError, onNameChange, KeyboardType.Text)
                    ProfileField("Email", state.email, state.emailError, onEmailChange, KeyboardType.Email)
                    ProfileField("Phone", state.phone, state.phoneError, onPhoneChange, KeyboardType.Phone)
                    Button(onClick = onSave, enabled = !state.saving && !state.deleting, modifier = Modifier.fillMaxWidth()) {
                        Text("Save changes")
                    }
                    state.message?.let { Text(it) }
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = state.notificationsEnabled, enabled = !state.saving && !state.deleting,
                            role = Role.Switch, onValueChange = onNotificationsChange,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Allow push notifications", Modifier.weight(1f))
                        Switch(checked = state.notificationsEnabled, onCheckedChange = null)
                    }
                    Text("When notifications are off, check your inbox for lottery results and reservation updates.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onPolicy) { Text("App policy & guidelines") }
                    TextButton(onClick = onDelete, enabled = !state.deleting) {
                        Text(if (state.deleting) "Deleting profile…" else "Delete profile", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Delete your profile?") },
            text = { Text("Your profile, waiting list entries and reservations will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text("Cancel") } },
        )
    }
}

/**
 * A single-line profile text field with an optional error message.
 * @param label The field label.
 * @param value The current text.
 * @param error Why the value is invalid, or null.
 * @param onChange Called with the new text.
 * @param keyboard The keyboard to show.
 */
@Composable
private fun ProfileField(label: String, value: String, error: String?, onChange: (String) -> Unit, keyboard: KeyboardType) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
    )
}
