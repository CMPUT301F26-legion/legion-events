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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.CampsiteFilter
import com.example.legion_events.controller.CampsiteListState
import com.example.legion_events.model.Campsite
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Displays active campsite listings with search and guest/price filters.
 * @param state Current listings, search, filter, and request state.
 * @param onSearch Changes the search text in the controller.
 * @param onFilter Changes the selected filter.
 * @param onRetry Reloads listings after a failed request.
 */
@Composable
fun HomeScreen(
    state: CampsiteListState,
    onSearch: (String) -> Unit,
    onFilter: (CampsiteFilter) -> Unit,
    onRetry: () -> Unit,
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
                OutlinedTextField(
                    value = state.query, onValueChange = onSearch,
                    label = { Text("Search campsites") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
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
                        else { onSearch(""); onFilter(CampsiteFilter.ALL) }
                    }) { Text(if (state.campsites.isEmpty()) "Refresh" else "Clear search and filters", color = accent) }
                }
                else -> {
                    item { Text("${listings.size} ${if (listings.size == 1) "campsite" else "campsites"}", style = MaterialTheme.typography.labelLarge) }
                    items(listings, key = { it.id }) { CampsiteListing(it) }
                }
            }
        }
    }
}

/** Shows listing information without opening a detail or editing screen. */
@Composable
private fun CampsiteListing(site: Campsite) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
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
private fun campsitePrice(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.CANADA).format(BigDecimal.valueOf(cents, 2)) + " CAD / night"
