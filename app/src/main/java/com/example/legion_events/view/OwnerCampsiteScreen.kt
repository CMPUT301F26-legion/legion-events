package com.example.legion_events.view

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.OwnerCampsiteState
import com.example.legion_events.data.cacheQrPng
import com.example.legion_events.data.createQrBitmap
import com.example.legion_events.data.writeQrPng
import com.example.legion_events.model.Campsite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * S14: shows one of the owner's campsites and its QR code.
 * @param state The campsite and request state from the controller.
 * @param onBack Returns to the owner dashboard.
 * @param onEdit Opens S13 prefilled with this campsite.
 * @param onRetry Reloads after a failed load.
 * @param onGenerateQr Creates the QR code for this campsite.
 */
@Composable
fun OwnerCampsiteScreen(
    state: OwnerCampsiteState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onGenerateQr: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        val site = state.campsite
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                BackArrow(onBack)
                CircularProgressIndicator(Modifier.padding(top = 12.dp))
            }
            state.error != null || site == null -> Column(Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BackArrow(onBack)
                Text(state.error ?: "This campsite is no longer available.")
                if (state.error != null) Button(onClick = onRetry) { Text("Retry") }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box {
                    CampsiteCover(state.cover, "${site.name} cover photo", Modifier.fillMaxWidth().height(200.dp))
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) { BackArrow(onBack) }
                        Spacer(Modifier.weight(1f))
                        // Surface colours keep the button readable over any cover photo.
                        Button(onClick = onEdit, colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )) { Text("✎ Edit") }
                    }
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(site.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f, fill = false))
                            state.badge?.let { LotteryBadgeChip(it) }
                        }
                        Text(locationLine(site), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("${site.maxGuests} guests", "Max occupancy", Modifier.weight(1f))
                        StatTile(shortPrice(site.pricePerNight), "Per night", Modifier.weight(1f))
                        StatTile(availability(site.availableNights), "Available", Modifier.weight(1.3f))
                    }
                    if (site.description.isNotBlank()) DetailBlock("About", site.description)
                    DetailBlock("Amenities", site.amenities.joinToString(" · ").ifBlank { "None listed." })
                    if (site.tags.isNotEmpty()) DetailBlock("Tags", site.tags.joinToString(" · "))
                    DetailBlock("Rules", site.rules.joinToString("\n").ifBlank { "No rules listed." })
                    DetailBlock("Cancellation policy", site.cancellationPolicy.ifBlank { "Not provided." })
                    QrCard(site, state.qrText, onGenerateQr)
                }
            }
        }
    }
}

/**
 * The QR card: Generate QR, then Download (save as PNG) and Share (Android share sheet).
 * @param site The campsite, used to name the file.
 * @param qrText The QR text once generated, or null.
 * @param onGenerate Creates the QR text.
 */
@Composable
private fun QrCard(site: Campsite, qrText: String?, onGenerate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(qrText) { mutableStateOf<Bitmap?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(qrText) { bitmap = qrText?.let { withContext(Dispatchers.Default) { createQrBitmap(it) } } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val image = bitmap
        if (uri != null && image != null) scope.launch {
            notice = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)) { "No file" }.use { writeQrPng(image, it) }
                }
                "QR code saved."
            } catch (_: Exception) {
                "Could not save the QR code."
            }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Campsite QR code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Entrants scan this to open the campsite page.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    when {
                        image != null -> Image(image.asImageBitmap(), "QR code for ${site.name}", Modifier.fillMaxSize())
                        qrText != null -> CircularProgressIndicator()
                        else -> Text("No QR code yet", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGenerate, enabled = qrText == null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (qrText == null) "Generate QR" else "Generated")
                    }
                    OutlinedButton(
                        onClick = { notice = null; save.launch("${fileName(site)}-qr.png") },
                        enabled = bitmap != null, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download") }
                    OutlinedButton(
                        onClick = {
                            val image = bitmap ?: return@OutlinedButton
                            notice = null
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) { cacheQrPng(context, image, site.id) }
                                    val send = Intent(Intent.ACTION_SEND).setType("image/png")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .putExtra(Intent.EXTRA_TEXT, "Scan to open ${site.name} in Legion Events.")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    send.clipData = ClipData.newRawUri(null, uri)
                                    context.startActivity(Intent.createChooser(send, "Share campsite QR code"))
                                } catch (_: IOException) {
                                    notice = "Could not prepare the QR code to share."
                                } catch (_: ActivityNotFoundException) {
                                    notice = "No app on this device can share images."
                                }
                            }
                        },
                        enabled = bitmap != null, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share") }
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * A titled block of detail text.
 * @param title The heading.
 * @param body The text.
 */
@Composable
private fun DetailBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Describes where the campsite is, as text and coordinates.
 * @param site The campsite.
 * @return For example `Banff National Park, AB · 51.2207, -115.4927`.
 */
private fun locationLine(site: Campsite): String {
    val point = site.location ?: return site.locationName
    return "${site.locationName} · ${"%.4f, %.4f".format(Locale.US, point.latitude, point.longitude)}"
}

/**
 * Describes the listed nights compactly.
 * @param nights Sorted `YYYY-MM-DD` nights.
 * @return For example `Jun 1 to Sep 15`, or `None` when no nights are listed.
 */
private fun availability(nights: List<String>): String {
    if (nights.isEmpty()) return "None"
    val read = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val write = SimpleDateFormat("MMM d", Locale.CANADA).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return try {
        val first = write.format(checkNotNull(read.parse(nights.first())))
        val last = write.format(checkNotNull(read.parse(nights.last())))
        if (nights.size == 1) first else "$first to $last"
    } catch (_: ParseException) {
        "${nights.size} nights"
    }
}

/**
 * Builds a safe file name from the campsite name.
 * @param site The campsite.
 * @return Lowercase letters, digits, and dashes, such as `two-jack-lakeside`.
 */
private fun fileName(site: Campsite): String =
    site.name.lowercase(Locale.CANADA).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "campsite" }
