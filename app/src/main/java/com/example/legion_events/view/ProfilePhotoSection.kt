/*
 * ProfilePhotoSection.kt
 *
 * Purpose: Display the profile image and image actions on S11.
 * Role: View in MVC; decodes display data off the main thread and forwards user actions.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.ProfilePhotoState
import com.example.legion_events.data.decodePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Displays saved image data with accessible add/change/remove actions, without database calls. */
@Composable
fun ProfilePhotoSection(state: ProfilePhotoState, enabled: Boolean, onPick: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit, onReload: () -> Unit) {
    val photo = state.photo
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo) {
        value = photo?.let { withContext(Dispatchers.Default) { decodePhoto(it) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.size(88.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) {
                val image = bitmap
                if (image != null) Image(image.asImageBitmap(), "Your profile image", Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                else Text("Profile image", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.working) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Updating profile image…") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, enabled = enabled && !state.working) { Text(if (photo == null) "Add image" else "Change image") }
            if (photo != null) TextButton(onClick = onRemove, enabled = enabled && !state.working) { Text("Remove image") }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = onRetry, enabled = !state.working) { Text("Retry image") }
                TextButton(onClick = onReload, enabled = !state.working) { Text("Reload image") }
            }
        }
    }
}
