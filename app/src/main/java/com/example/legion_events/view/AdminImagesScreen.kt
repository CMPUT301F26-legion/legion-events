/*
 * AdminImagesScreen.kt
 *
 * Purpose: S19 Images tab: a grid of every uploaded image with its campsite and owner, and a
 *   confirmed removal for each (US 03.06.01, 03.03.01).
 * Role: View in MVC; renders AdminImagesState and forwards input to AdminImagesController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.AdminImagesState

/**
 * Shows every uploaded image to an admin, two per row.
 * @param state The images and any pending removal.
 * @param onBack Returns to the profile screen.
 * @param onRetry Reloads after a failure.
 * @param onRemove Asks to remove the image with this ID.
 * @param onConfirmRemove Deletes the image being confirmed.
 * @param onCancelRemove Closes the confirmation.
 */
@Composable
fun AdminImagesScreen(
    state: AdminImagesState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRemove: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fullWidth {
                AdminHeader("Uploaded images", onBack)
                state.result?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            when {
                state.loading && state.rows.isEmpty() -> fullWidth { AdminLoading("Loading images…") }
                state.error != null -> fullWidth {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                state.rows.isEmpty() -> fullWidth {
                    Text("No images have been uploaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(state.rows, key = { it.photo.id }) { row ->
                    AdminCard {
                        CampsiteCover(row.photo, "Image of ${row.placeName}", Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(row.placeName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("By ${row.ownerName}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.cover) Text(if (row.photo.targetType == "user") "Profile picture" else "Cover photo", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onRemove(row.photo.id) }, enabled = !state.removing) {
                            Text("Remove image", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    state.confirming?.let { pending ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text("Remove this image?") },
            text = {
                Text(
                    "The image is deleted for everyone and cannot be restored." +
                        if (pending.cover && pending.photo.targetType != "user") " ${pending.placeName} will show no cover photo." else ""
                )
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
 * Adds a grid item that spans both columns.
 * @param content The item's content.
 */
private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { Column { content() } }
}
