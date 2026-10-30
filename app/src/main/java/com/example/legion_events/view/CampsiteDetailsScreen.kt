package com.example.legion_events.view

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.CampsiteDetailsState
import com.example.legion_events.data.decodePhoto
import com.example.legion_events.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Displays a campsite, its photos, and owner-listed nights without changing database records.
 * @param state Listing and photo loading results supplied by the controller.
 * @param onBack Returns to the existing search screen.
 * @param onRetry Reloads an unavailable or failed listing.
 * @param onRetryPhotos Reloads photos while keeping campsite information visible.
 */
@Composable
fun CampsiteDetailsScreen(
    state: CampsiteDetailsState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryPhotos: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { TextButton(onClick = onBack) { Text("Back to search") } }
            val site = state.campsite
            when {
                state.loading -> item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Loading campsite…", Modifier.padding(top = 12.dp))
                    }
                }
                state.error != null || site == null -> item {
                    Text(state.error ?: "This campsite is no longer available.")
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    item {
                        Text(site.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(site.locationName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        when {
                            state.photosLoading -> Text("Loading photos…")
                            state.photosError != null -> {
                                Text(state.photosError)
                                TextButton(onClick = onRetryPhotos) { Text("Retry photos") }
                            }
                            state.photos.isEmpty() -> Box(
                                Modifier.fillMaxWidth().height(160.dp).background(
                                    MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text("No photos yet") }
                            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(state.photos, key = { _, photo -> photo.id }) { index, photo ->
                                    CampsitePhoto(photo, "${site.name} photo ${index + 1}")
                                }
                            }
                        }
                    }
                    item {
                        Text(campsitePrice(site.pricePerNight), style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Up to ${site.maxGuests} guests")
                        if (site.ratingCount > 0) {
                            val label = if (site.ratingCount == 1L) "rating" else "ratings"
                            Text(String.format(Locale.CANADA, "%.1f / 5 · %d %s", site.ratingAverage, site.ratingCount, label))
                        } else Text("No ratings yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { DetailSection("About this campsite", site.description.ifBlank { "No description provided." }) }
                    item { DetailSection("Amenities", site.amenities.joinToString(" · ").ifBlank { "Not provided." }) }
                    if (site.tags.isNotEmpty()) item { DetailSection("Tags", site.tags.joinToString(" · ")) }
                    site.location?.let { point ->
                        item { DetailSection("Coordinates", String.format(Locale.CANADA, "%.5f, %.5f", point.latitude, point.longitude)) }
                    }
                    item { DetailSection("Campsite rules", site.rules.joinToString("\n").ifBlank { "No rules provided." }) }
                    item { DetailSection("Cancellation policy", site.cancellationPolicy.ifBlank { "Not provided." }) }
                    item {
                        DetailSection("Owner-listed nights", site.availableNights.distinct().sorted().joinToString("\n")
                            .ifBlank { "No nights listed." })
                        Text("Listed nights may change.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        // Entry is intentionally unavailable until the lottery flow is implemented.
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Enter lottery") }
                        Text("Lottery entry is coming soon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Displays a named section with the same typography and spacing across campsite details. */
@Composable
private fun DetailSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body)
    }
}

/** Decodes only composed gallery images off the main thread and shows a fallback for corrupt photos. */
@Composable
private fun CampsitePhoto(photo: Photo, description: String) {
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(photo) { mutableStateOf(true) }
    LaunchedEffect(photo) {
        bitmap = withContext(Dispatchers.Default) { decodePhoto(photo) }
        loading = false
    }
    Box(
        Modifier.width(280.dp).height(200.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            loading -> CircularProgressIndicator()
            image != null -> Image(image.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Text("Photo unavailable")
        }
    }
}
