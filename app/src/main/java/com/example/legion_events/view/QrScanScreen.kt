/*
 * QrScanScreen.kt
 *
 * Purpose: S05: launches the QR camera and shows invalid-code and permission recovery states (US 01.12.01).
 * Role: View in MVC; renders QrScanState.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.QrScanState
import com.example.legion_events.controller.QrScanStatus
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * S05: opens the QR-only camera and renders invalid-code and permission recovery states.
 * @param state Scanner state supplied by its controller.
 * @param onScanStarted Marks a camera launch before it happens.
 * @param onScanResult Sends scanned text, or null when scanning was cancelled.
 * @param onCameraFailed Reports camera failure; true means camera permission was denied.
 * @param onRetry Requests a new camera launch after a failure.
 * @param onBack Returns to search without opening a campsite.
 */
@Composable
fun QrScanScreen(
    state: QrScanState,
    onScanStarted: () -> Unit,
    onScanResult: (String?) -> Unit,
    onCameraFailed: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            onCameraFailed(true)
        } else onScanResult(result.contents)
    }
    LaunchedEffect(state.status) {
        if (state.status == QrScanStatus.READY) {
            onScanStarted()
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                onCameraFailed(false)
            } else try {
                scanner.launch(ScanOptions().setCaptureActivity(QrCaptureActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Point the camera at a campsite QR code")
                    .setOrientationLocked(false).setBeepEnabled(false).setBarcodeImageEnabled(false)
                    .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false))
            } catch (_: ActivityNotFoundException) {
                onCameraFailed(false)
            } catch (_: SecurityException) {
                onCameraFailed(true)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text("Scan QR code", style = MaterialTheme.typography.headlineSmall)
            }
            Text(when (state.status) {
                QrScanStatus.INVALID -> "This is not a campsite QR code. Scan a code shared from a campsite listing."
                QrScanStatus.PERMISSION_DENIED -> "Camera permission is needed to scan a campsite QR code."
                QrScanStatus.FAILED -> "Could not open the camera. Please retry on a device with a camera."
                QrScanStatus.FOUND -> "Opening campsite…"
                QrScanStatus.CANCELLED -> "Returning to search…"
                else -> "Point the camera at a campsite QR code."
            })
            if (state.status in setOf(QrScanStatus.INVALID, QrScanStatus.PERMISSION_DENIED, QrScanStatus.FAILED)) {
                Button(onClick = onRetry) { Text("Retry scan") }
            }
            if (state.status == QrScanStatus.PERMISSION_DENIED) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open app settings") }
            }
        }
    }
}
