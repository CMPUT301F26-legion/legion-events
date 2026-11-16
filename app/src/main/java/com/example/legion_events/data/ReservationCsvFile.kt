/*
 * ReservationCsvFile.kt
 *
 * Purpose: Save a confirmation CSV to the document chosen by the entrant.
 * Role: Data layer file helper; keeps output-stream access out of views and controllers.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Writes UTF-8 CSV off the main thread and closes the selected document's stream.
 * @param resolver Opens the document granted by Android's Save dialog.
 * @param destination The user-selected content URI; no storage permission is required.
 * @param csv Receipt text prepared from an owned reservation.
 */
internal suspend fun writeReservationCsv(resolver: ContentResolver, destination: Uri, csv: String) {
    withContext(Dispatchers.IO) {
        val stream = resolver.openOutputStream(destination, "wt") ?: throw IOException("Could not open the document.")
        stream.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
    }
}
