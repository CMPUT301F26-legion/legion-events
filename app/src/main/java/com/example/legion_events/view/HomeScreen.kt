/*
 * HomeScreen.kt
 *
 * Purpose: S02/S03: campsite search with guest and price filters, and the QR scan entry (US 01.01.01, 01.01.03, 01.01.04).
 * Role: View in MVC; renders CampsiteListState.
 * Outstanding issues: The location filter (US 01.01.02) is a later part; the date filter checks offered nights, not bookings.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.CampsiteFilter
import com.example.legion_events.controller.CampsiteListState
import com.example.legion_events.model.Campsite
import com.example.legion_events.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Displays active campsite listings with search and guest/price filters.
 * @param state Current listings, search, filter, and request state.
 * @param onSearch Changes the search text in the controller.
 * @param onFilter Changes the selected filter.
 * @param onRetry Reloads listings after a failed request.
 * @param onScanQr Opens the campsite QR scanner from the search field.
 * @param onAvailableAt Sets the `YYYY-MM-DD` night campsites must offer, or null for any date.
 * @param onGuestsMoreThan Sets the guest count capacity must exceed, or null to turn it off.
 * @param onGuestsLessThan Sets the guest count capacity must be below, or null to turn it off.
 * @param onCampsiteClick Opens a campsite using its document ID.
 */
@Composable
fun HomeScreen(
    state: CampsiteListState,
    onSearch: (String) -> Unit,
    onFilter: (CampsiteFilter) -> Unit,
    onRetry: () -> Unit,
    onScanQr: () -> Unit,
    onAvailableAt: (String?) -> Unit = {},
    onGuestsMoreThan: (Int?) -> Unit = {},
    onGuestsLessThan: (Int?) -> Unit = {},
    onCampsiteClick: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val focus = LocalFocusManager.current
    val listings = remember(state) { state.visible }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Explore campsites", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Search by name, area, or amenity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AvailableAtField(state.availableAt, onAvailableAt)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuestField("Guests more than", state.guestsMoreThan, onGuestsMoreThan, Modifier.weight(1f))
                        GuestField("Guests less than", state.guestsLessThan, onGuestsLessThan, Modifier.weight(1f))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.query, onValueChange = onSearch,
                    label = { Text("Search campsites") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    trailingIcon = {
                        IconButton(onClick = onScanQr) {
                            Icon(painterResource(R.drawable.ic_qr_code_scan), "Scan campsite QR code")
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent,
                        focusedLabelColor = accent, cursorColor = accent),
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((filter, label) in listOf(CampsiteFilter.ALL to "All sites",
                        CampsiteFilter.GROUPS to "4+ guests", CampsiteFilter.BUDGET to "$100 or less")) {
                        FilterChip(selected = state.filter == filter, onClick = { onFilter(filter) },
                            label = { Text(label) }, colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                    }
                }
            }
            when {
                state.loading -> item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accent)
                        Text("Loading campsites…", Modifier.padding(top = 12.dp))
                    }
                }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Retry") }
                }
                listings.isEmpty() -> item {
                    Text(if (state.campsites.isEmpty()) "No campsites are listed yet." else "No matching campsites.")
                    TextButton(onClick = {
                        if (state.campsites.isEmpty()) onRetry()
                        else {
                            onSearch(""); onFilter(CampsiteFilter.ALL)
                            onAvailableAt(null); onGuestsMoreThan(null); onGuestsLessThan(null)
                        }
                    }) { Text(if (state.campsites.isEmpty()) "Refresh" else "Clear search and filters", color = accent) }
                }
                else -> {
                    item { Text("${listings.size} ${if (listings.size == 1) "campsite" else "campsites"}", style = MaterialTheme.typography.labelLarge) }
                    items(listings, key = { it.id }) { site -> CampsiteListing(site) { onCampsiteClick(site.id) } }
                }
            }
        }
    }
}

/**
 * A read-only date field that opens a date picker; Clear turns the date filter off.
 * @param date The chosen `YYYY-MM-DD` night, or null for any date.
 * @param onPick Called with the picked night, or null when cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvailableAtField(date: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = date.orEmpty(), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Available at") }, placeholder = { Text("Any date") },
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        // A transparent layer opens the picker, since a read-only field does not report taps.
        Surface(onClick = { open = true }, color = Color.Transparent, modifier = Modifier.matchParentSize()) {}
        if (date != null) TextButton(onClick = { onPick(null) },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { Text("Clear") }
    }
    if (open) {
        val initial = date?.let { runCatching { nightDayFormat().parse(it)?.time }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onPick(nightDayFormat().format(it)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }
}

/**
 * A number-only guest count field; empty turns that filter off.
 * @param label The field label.
 * @param guests The current count, or null when off.
 * @param onChange Called with the new count, or null when the field is emptied.
 */
@Composable
private fun GuestField(label: String, guests: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = guests?.toString().orEmpty(),
        onValueChange = { text -> text.filter(Char::isDigit).take(3).let { onChange(it.toIntOrNull()) } },
        label = { Text(label) }, placeholder = { Text("Any") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp), modifier = modifier,
    )
}

/** DatePicker works in UTC milliseconds, matching the UTC `YYYY-MM-DD` night keys. */
private fun nightDayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Shows listing information and forwards taps to the shared router through the screen callback. */
@Composable
private fun CampsiteListing(site: Campsite, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(site.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(site.locationName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Up to ${site.maxGuests} guests")
            Text(campsitePrice(site.pricePerNight), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Formats integer CAD cents without rounding a floating-point price. */
internal fun campsitePrice(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.CANADA).format(BigDecimal.valueOf(cents, 2)) + " CAD / night"
