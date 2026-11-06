/*
 * LotteryEditScreen.kt
 *
 * Purpose: S15: the create/edit lottery form (US 02.04.01 to 02.04.07).
 * Role: View in MVC; renders LotteryEditState and forwards input to LotteryEditController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.LotteryEditState
import com.example.legion_events.controller.LotteryField
import com.example.legion_events.model.MAX_WAITLIST_LIMIT
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * S15: creates or edits a lottery (US 02.04.01–02.04.07).
 * @param state Form values and request state from the controller.
 * @param onBack Leaves without saving.
 * @param onRetry Reloads after a failed load.
 * @param onToggleCampsite Adds or removes a campsite by ID.
 * @param onRegistrationStart Sets the first registration day (`YYYY-MM-DD`).
 * @param onRegistrationEnd Sets the last registration day.
 * @param onBookingStart Sets the first booking day.
 * @param onBookingEnd Sets the last booking day.
 * @param onWinnersChange Sends number-of-winners edits.
 * @param onLimitChange Sends waitlist limit edits; empty saves the maximum.
 * @param onSave Validates and saves.
 */
@Composable
fun LotteryEditScreen(
    state: LotteryEditState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleCampsite: (String) -> Unit,
    onRegistrationStart: (String) -> Unit,
    onRegistrationEnd: (String) -> Unit,
    onBookingStart: (String) -> Unit,
    onBookingEnd: (String) -> Unit,
    onWinnersChange: (String) -> Unit,
    onLimitChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val enabled = !state.saving && !state.locked
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(if (state.editing) "Edit lottery" else "Create lottery",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            when {
                state.loading -> CircularProgressIndicator()
                state.loadError != null -> {
                    Text(state.loadError, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Section("Campsites")
                    if (state.campsites.isEmpty()) {
                        Text("You have no campsites yet. Add an active campsite before creating a lottery.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.campsites.forEach { campsite ->
                        val checked = campsite.id in state.selectedCampsiteIds
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = checked, enabled = enabled, role = Role.Checkbox,
                                onValueChange = { onToggleCampsite(campsite.id) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                            Spacer(Modifier.width(8.dp))
                            Text(listOf(campsite.name, campsite.locationName).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { campsite.id })
                        }
                    }
                    FieldError(state.errors[LotteryField.CAMPSITES])

                    Section("Registration period")
                    DayButton("Opens", state.registrationStart, enabled, onRegistrationStart)
                    DayButton("Closes (last day)", state.registrationEnd, enabled, onRegistrationEnd)
                    FieldError(state.errors[LotteryField.REGISTRATION])

                    Section("Booking period")
                    DayButton("Opens", state.bookingStart, enabled, onBookingStart)
                    DayButton("Closes (last day)", state.bookingEnd, enabled, onBookingEnd)
                    FieldError(state.errors[LotteryField.BOOKING])

                    NumberField("Number of winners", state.winnerCount, state.errors[LotteryField.WINNERS], enabled, onWinnersChange)
                    NumberField("Waitlist limit (optional)", state.waitlistLimit,
                        state.errors[LotteryField.LIMIT] ?: "Leave empty for the maximum of $MAX_WAITLIST_LIMIT.",
                        enabled, onLimitChange, isError = LotteryField.LIMIT in state.errors)

                    Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(when {
                            state.saving -> "Saving…"
                            state.editing -> "Save changes"
                            else -> "Create lottery"
                        })
                    }
                }
            }
        }
    }
}

/** A section heading. */
@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** An error line under a group of controls, or nothing. */
@Composable
private fun FieldError(error: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/**
 * A numeric text field.
 * @param supporting Error or hint text shown under the field.
 */
@Composable
private fun NumberField(
    label: String, value: String, supporting: String?, enabled: Boolean, onChange: (String) -> Unit,
    isError: Boolean = supporting != null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = isError, supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

/** DatePicker works in UTC milliseconds, so days are converted in UTC. */
private fun utcDayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * A button showing a chosen day that opens a date picker.
 * @param day The chosen `YYYY-MM-DD` day, or empty.
 * @param onPick Called with the picked `YYYY-MM-DD` day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayButton(label: String, day: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${day.ifEmpty { "choose" }}")
    }
    if (open) {
        val initial = day.takeIf { it.isNotEmpty() }?.let { runCatching { utcDayFormat().parse(it)?.time }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onPick(utcDayFormat().format(it)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }
}