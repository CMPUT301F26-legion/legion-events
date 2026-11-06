/*
 * CampsiteEditScreen.kt
 *
 * Purpose: S13: the add/edit campsite form (US 02.01.01 to 02.01.03).
 * Role: View in MVC; renders CampsiteEditState and forwards input to CampsiteEditController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.CampsiteEditState
import com.example.legion_events.controller.CampsiteField
import com.example.legion_events.controller.utcDayFormat
import com.example.legion_events.data.currentLocation
import com.example.legion_events.data.encodeCampsitePhoto
import com.example.legion_events.data.hasLocationPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.ParseException

/**
 * Callbacks from S13 to its controller.
 * @property onBack Leaves without saving.
 * @property onRetry Reloads an edited campsite after a failed load.
 * @property onName Sends name edits.
 * @property onLocationName Sends area or town edits.
 * @property onDescription Sends description edits.
 * @property onToggleAmenity Selects or clears an amenity.
 * @property onTagInput Sends the tag being typed.
 * @property onAddTag Adds the typed tag.
 * @property onRemoveTag Removes a tag.
 * @property onMaxGuests Sends occupancy edits.
 * @property onPrice Sends price edits.
 * @property onLatitude Sends latitude edits.
 * @property onLongitude Sends longitude edits.
 * @property onLocating Reports that the device location is being read.
 * @property onCurrentLocation Sends the device's latitude and longitude.
 * @property onLocationFailed Reports why the device location could not be used.
 * @property onRules Sends rules edits.
 * @property onCancellationPolicy Selects a cancellation policy.
 * @property onOpenFrom Sets the first listed night (`YYYY-MM-DD`).
 * @property onOpenUntil Sets the last listed night (`YYYY-MM-DD`).
 * @property onPhotoPicked Reports that a photo is being compressed.
 * @property onPhoto Sends the compressed photo, or null if it was unreadable.
 * @property onRemovePhoto Discards the newly picked photo.
 * @property onSave Validates and saves.
 */
data class CampsiteEditActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onName: (String) -> Unit,
    val onLocationName: (String) -> Unit,
    val onDescription: (String) -> Unit,
    val onToggleAmenity: (String) -> Unit,
    val onTagInput: (String) -> Unit,
    val onAddTag: () -> Unit,
    val onRemoveTag: (String) -> Unit,
    val onMaxGuests: (String) -> Unit,
    val onPrice: (String) -> Unit,
    val onLatitude: (String) -> Unit,
    val onLongitude: (String) -> Unit,
    val onLocating: () -> Unit,
    val onCurrentLocation: (Double, Double) -> Unit,
    val onLocationFailed: (String) -> Unit,
    val onRules: (String) -> Unit,
    val onCancellationPolicy: (String) -> Unit,
    val onOpenFrom: (String) -> Unit,
    val onOpenUntil: (String) -> Unit,
    val onPhotoPicked: () -> Unit,
    val onPhoto: (String?) -> Unit,
    val onRemovePhoto: () -> Unit,
    val onSave: () -> Unit,
)

/**
 * S13: adds a campsite (blank form) or edits one (prefilled from S14).
 * @param state Form values and request state from the controller.
 * @param actions Sends edits and actions to the controller.
 */
@Composable
fun CampsiteEditScreen(state: CampsiteEditState, actions: CampsiteEditActions) {
    val enabled = !state.saving
    val requesters = remember { CampsiteField.entries.associateWith { BringIntoViewRequester() } }
    // After a rejected save, scroll to the first invalid field so the user sees why.
    LaunchedEffect(state.failedSaves) {
        if (state.failedSaves > 0) state.errors.keys.minOrNull()?.let { requesters.getValue(it).bringIntoView() }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // The header and save result stay visible while the form scrolls.
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BackArrow(actions.onBack)
                    Text(if (state.editing) "Edit Campsite" else "Add Campsite", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (!state.loading && state.loadError == null) {
                        TextButton(onClick = actions.onSave, enabled = enabled && !state.encodingPhoto) {
                            Text(if (state.saving) "Saving…" else "Save")
                        }
                    }
                }
                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(12.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadError != null -> {
                        Text(state.loadError, color = MaterialTheme.colorScheme.error)
                        Button(onClick = actions.onRetry) { Text("Retry") }
                    }
                    else -> CampsiteForm(state, actions, enabled) { requesters.getValue(it) }
                }
            }
        }
    }
}

/**
 * The form fields of S13, in mockup order.
 * @param state Form values from the controller.
 * @param actions Sends edits to the controller.
 * @param enabled False while saving.
 * @param requester Finds the scroll target for a field, used to show the first error.
 */
@Composable
private fun CampsiteForm(
    state: CampsiteEditState, actions: CampsiteEditActions, enabled: Boolean,
    requester: (CampsiteField) -> BringIntoViewRequester,
) {
    /** Marks a field as the scroll target for its error. */
    fun Modifier.target(field: CampsiteField) = bringIntoViewRequester(requester(field))
    Section("Basic info")
    FormField("Campsite name *", state.name, state.errors[CampsiteField.NAME], enabled, actions.onName,
        modifier = Modifier.target(CampsiteField.NAME))
    FormField("Location *", state.locationName, state.errors[CampsiteField.LOCATION], enabled, actions.onLocationName,
        modifier = Modifier.target(CampsiteField.LOCATION), placeholder = "Area or town, such as Banff National Park, AB")
    FormField("Description", state.description, state.errors[CampsiteField.DESCRIPTION], enabled, actions.onDescription,
        singleLine = false, modifier = Modifier.target(CampsiteField.DESCRIPTION))

    Section("Amenities")
    ChipRow {
        state.amenityOptions.forEach { amenity ->
            FilterChip(selected = amenity in state.amenities, onClick = { actions.onToggleAmenity(amenity) },
                label = { Text(amenity) }, enabled = enabled,
                leadingIcon = if (amenity in state.amenities) { { Text("✓") } } else null)
        }
    }

    Section("Capacity and price")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField("Max occupancy *", state.maxGuests, state.errors[CampsiteField.GUESTS], enabled, actions.onMaxGuests,
            keyboard = KeyboardType.Number, modifier = Modifier.weight(1f).target(CampsiteField.GUESTS), suffix = "guests")
        FormField("Price per night *", state.price, state.errors[CampsiteField.PRICE], enabled, actions.onPrice,
            keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f).target(CampsiteField.PRICE), prefix = "$")
    }

    Section("Map coordinates")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField("Latitude *", state.latitude, null, enabled, actions.onLatitude,
            keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f).target(CampsiteField.COORDINATES), isError = CampsiteField.COORDINATES in state.errors)
        FormField("Longitude *", state.longitude, null, enabled, actions.onLongitude,
            keyboard = KeyboardType.Decimal, modifier = Modifier.weight(1f), isError = CampsiteField.COORDINATES in state.errors)
    }
    FieldError(state.errors[CampsiteField.COORDINATES])
    UseCurrentLocationButton(state.locating, enabled, actions)
    state.locationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    Section("Photo (optional)")
    PhotoPicker(state, enabled, actions)

    Section("Tags")
    if (state.tags.isNotEmpty()) {
        ChipRow {
            state.tags.forEach { tag ->
                InputChip(selected = false, onClick = { actions.onRemoveTag(tag) }, label = { Text(tag) }, enabled = enabled,
                    trailingIcon = { Text("✕", style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
    OutlinedTextField(
        value = state.tagInput, onValueChange = actions.onTagInput, label = { Text("Add tag") },
        placeholder = { Text("Type and press Enter") }, singleLine = true, enabled = enabled,
        isError = CampsiteField.TAG in state.errors, supportingText = state.errors[CampsiteField.TAG]?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onAddTag() }),
        trailingIcon = { TextButton(onClick = actions.onAddTag, enabled = enabled && state.tagInput.isNotBlank()) { Text("Add") } },
        modifier = Modifier.fillMaxWidth().target(CampsiteField.TAG),
    )

    Section("Rules and policies")
    FormField("Rules *", state.rules, state.errors[CampsiteField.RULES], enabled, actions.onRules, singleLine = false,
        modifier = Modifier.target(CampsiteField.RULES), placeholder = "One rule per line")
    PolicyMenu(state, enabled, actions.onCancellationPolicy, Modifier.target(CampsiteField.CANCELLATION))

    Section("Availability")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DayField("Open from *", state.openFrom, enabled, CampsiteField.AVAILABILITY in state.errors,
            actions.onOpenFrom, Modifier.weight(1f).target(CampsiteField.AVAILABILITY))
        DayField("Open until *", state.openUntil, enabled, CampsiteField.AVAILABILITY in state.errors,
            actions.onOpenUntil, Modifier.weight(1f))
    }
    FieldError(state.errors[CampsiteField.AVAILABILITY])
    if (state.nightsHadGaps) {
        Text("Saving lists every night in this range, including nights that were left out before.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = actions.onBack, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = actions.onSave, enabled = enabled && !state.encodingPhoto, modifier = Modifier.weight(1f)) {
            Text(if (state.saving) "Saving…" else "Save")
        }
    }
}

/**
 * Asks for location permission if needed, then fills the coordinates.
 * @param locating Whether a request is already running.
 * @param enabled False while saving.
 * @param actions Receives the location or the reason it failed.
 */
@Composable
private fun UseCurrentLocationButton(locating: Boolean, enabled: Boolean, actions: CampsiteEditActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locate = {
        actions.onLocating()
        scope.launch {
            val location = currentLocation(context.applicationContext)
            if (location != null) actions.onCurrentLocation(location.latitude, location.longitude)
            else actions.onLocationFailed("Could not find your location. Turn on location or enter coordinates.")
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate()
        else actions.onLocationFailed("Location permission was denied. Enter coordinates instead.")
    }
    TextButton(
        onClick = {
            if (hasLocationPermission(context)) locate()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        enabled = enabled && !locating,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (locating) "Finding your location…" else "⌖ Use current location") }
}

/**
 * Opens the photo picker and compresses the chosen image off the main thread.
 * @param state Shows the chosen or existing photo.
 * @param enabled False while saving.
 * @param actions Receives the compressed photo.
 */
@Composable
private fun PhotoPicker(state: CampsiteEditState, enabled: Boolean, actions: CampsiteEditActions) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            actions.onPhotoPicked()
            scope.launch { actions.onPhoto(withContext(Dispatchers.IO) { encodeCampsitePhoto(resolver, uri) }) }
        }
    }
    OutlinedCard(
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = enabled && !state.encodingPhoto,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⇪", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    state.encodingPhoto -> "Preparing photo…"
                    state.newPhoto != null -> "New cover photo ready. Tap to choose another."
                    state.hasCover -> "A cover photo is saved. Tap to replace it."
                    else -> "Upload campsite photo (JPG or PNG)"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.newPhoto != null) TextButton(onClick = actions.onRemovePhoto, enabled = enabled) { Text("Remove new photo") }
    state.photoMessage?.let { FieldError(it) }
}

/**
 * The cancellation policy dropdown.
 * @param state Supplies the options, selection, and error.
 * @param enabled False while saving.
 * @param onSelect Called with the chosen policy.
 * @param modifier Applied to the dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyMenu(state: CampsiteEditState, enabled: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = state.cancellationPolicy, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text("Cancellation policy *") }, placeholder = { Text("Choose a policy") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            isError = CampsiteField.CANCELLATION in state.errors,
            supportingText = state.errors[CampsiteField.CANCELLATION]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.cancellationOptions.forEach { policy ->
                DropdownMenuItem(text = { Text(policy) }, onClick = { onSelect(policy); expanded = false })
            }
        }
    }
}

/**
 * A read-only field showing a night that opens a date picker when tapped.
 * @param label The field label.
 * @param day The chosen `YYYY-MM-DD` night, or empty.
 * @param enabled False while saving.
 * @param isError Whether to outline the field as invalid.
 * @param onPick Called with the picked `YYYY-MM-DD` night.
 * @param modifier Sizes the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayField(
    label: String, day: String, enabled: Boolean, isError: Boolean, onPick: (String) -> Unit, modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(value = day, onValueChange = {}, readOnly = true, enabled = enabled, isError = isError,
            label = { Text(label) }, placeholder = { Text("Choose") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // A transparent layer opens the picker, since a read-only field does not report taps.
        Surface(onClick = { open = true }, enabled = enabled, color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.matchParentSize()) {}
    }
    if (open) {
        val initial = day.takeIf(String::isNotEmpty)?.let {
            try { utcDayFormat().parse(it)?.time } catch (_: ParseException) { null }
        }
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

/**
 * A labelled text field with an optional inline error.
 * @param label The field label.
 * @param value The current text.
 * @param error Why the value is invalid, or null.
 * @param enabled False while saving.
 * @param onChange Called with the new text.
 * @param modifier Sizes the field; it always fills the width it is given.
 * @param keyboard The keyboard to show.
 * @param singleLine False for multi-line text.
 * @param placeholder Hint text shown while empty.
 * @param prefix Text before the value, such as `$`.
 * @param suffix Text after the value, such as `guests`.
 * @param isError Outlines the field as invalid even without its own error text.
 */
@Composable
private fun FormField(
    label: String, value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
    placeholder: String? = null, prefix: String? = null, suffix: String? = null, isError: Boolean = error != null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, enabled = enabled,
        minLines = if (singleLine) 1 else 3, isError = isError, supportingText = error?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } }, prefix = prefix?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().then(modifier),
    )
}

/**
 * Wraps chips onto as many lines as needed.
 * @param content The chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * A section heading.
 * @param title The heading text.
 */
@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/**
 * An error line under a group of fields, or nothing.
 * @param error The error, or null.
 */
@Composable
private fun FieldError(error: String?) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
