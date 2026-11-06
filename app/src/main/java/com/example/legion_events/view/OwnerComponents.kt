/*
 * OwnerComponents.kt
 *
 * Purpose: Small composables shared by the owner screens: back arrow, lottery badge, stat tile, cover photo.
 * Role: View helpers in MVC.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.R
import com.example.legion_events.controller.LotteryBadge
import com.example.legion_events.data.decodePhoto
import com.example.legion_events.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * A shared back arrow used at the top of owner and offer screens.
 * @param onBack Returns to the previous screen.
 * @param enabled Whether navigation is available while a screen action is pending.
 */
@Composable
internal fun BackArrow(onBack: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onBack, enabled = enabled) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}

/**
 * A small pill showing a campsite's lottery state.
 * @param badge The lottery state.
 */
@Composable
internal fun LotteryBadgeChip(badge: LotteryBadge) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (badge) {
        LotteryBadge.OPEN -> colors.primaryContainer to colors.onPrimaryContainer
        LotteryBadge.DRAWN -> colors.tertiaryContainer to colors.onTertiaryContainer
        LotteryBadge.UPCOMING, LotteryBadge.CLOSED -> colors.secondaryContainer to colors.onSecondaryContainer
        LotteryBadge.NONE -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Text(
        badge.label, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(container, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * A boxed number with a caption, used in the stat rows of S12 and S14.
 * @param value The main value.
 * @param label The caption under it.
 * @param modifier Sizes the tile.
 */
@Composable
internal fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shows a campsite cover photo, decoded off the main thread, or a placeholder.
 * @param photo The cover photo, or null for the placeholder.
 * @param description What the photo shows, for screen readers.
 * @param modifier Sizes and shapes the image.
 */
@Composable
internal fun CampsiteCover(photo: Photo?, description: String, modifier: Modifier = Modifier) {
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo) { bitmap = photo?.let { withContext(Dispatchers.Default) { decodePhoto(it) } } }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("⛰", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * Formats integer CAD cents compactly, such as `$38` or `$38.50`.
 * @param cents The price in cents.
 * @return The formatted price.
 */
internal fun shortPrice(cents: Long): String {
    val format = NumberFormat.getCurrencyInstance(Locale.CANADA)
    if (cents % 100 == 0L) format.minimumFractionDigits = 0
    return format.format(BigDecimal.valueOf(cents, 2))
}
