package com.example.legion_events.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A last known position younger than this is used without asking for a new fix. */
private const val FRESH_MILLIS = 10 * 60 * 1000L

/** How long to wait for a new position before giving up. */
private const val TIMEOUT_MILLIS = 20_000L

/**
 * Checks whether the user allowed approximate or precise location.
 * @param context The app context.
 * @return True when either location permission is granted.
 */
internal fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Finds the device's position for "Use current location" on S13.
 * Uses a recent last known position when one exists, otherwise asks for a new fix.
 * @param context The app context.
 * @return The position, or null without permission, with location off, or after a timeout.
 */
internal suspend fun currentLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
        runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
    }
    if (providers.isEmpty()) return null
    val lastKnown = try {
        providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) {
        return null
    }
    if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < FRESH_MILLIS) return lastKnown
    val fresh = withTimeoutOrNull(TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, providers.first(), signal, ContextCompat.getMainExecutor(context),
                ) { location -> continuation.resume(location) }
            } catch (_: SecurityException) {
                continuation.resume(null)
            }
        }
    }
    return fresh ?: lastKnown
}
