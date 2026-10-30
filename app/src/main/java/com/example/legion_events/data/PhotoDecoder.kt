package com.example.legion_events.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.legion_events.model.Photo

/**
 * Converts a stored photo into a display-sized bitmap; returns null for invalid image data.
 * Call from a background dispatcher so conversion and decoding do not block scrolling.
 * @param photo Compressed photo from Firestore.
 * @return Bitmap sampled to fit within 1200 pixels per side, or null if unreadable or unsupported.
 */
internal fun decodePhoto(photo: Photo): Bitmap? = try {
    if (photo.mimeType !in setOf("image/jpeg", "image/png", "image/webp")) null
    else {
        val bytes = Base64.decode(photo.imageBase64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            // Read dimensions first to avoid allocating a full camera-resolution bitmap.
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (bounds.outWidth > 1200L * options.inSampleSize || bounds.outHeight > 1200L * options.inSampleSize) {
                options.inSampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }
} catch (_: IllegalArgumentException) {
    null
}
