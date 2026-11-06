/*
 * PhotoEncoder.kt
 *
 * Purpose: Shrinks and compresses a picked image into the Base64 JPEG the schema stores.
 * Role: Data layer helper used by CampsiteEditScreen.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

/** Longest side of a stored campsite photo, in pixels. */
private const val MAX_SIDE = 1024

/** Largest stored JPEG. The Firestore rules allow 280,000 Base64 characters, which is 210,000 bytes. */
private const val MAX_BYTES = 200_000

/**
 * Converts a picked image into the schema's Base64 JPEG photo data.
 * Call from a background dispatcher; it reads and compresses the whole image.
 * @param resolver Opens the picked image.
 * @param uri The image chosen in the photo picker.
 * @return Base64 JPEG data, or null if the image cannot be read or made small enough.
 */
internal fun encodeCampsitePhoto(resolver: ContentResolver, uri: Uri): String? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
    else {
        // Sample while decoding so a camera-sized photo is never fully allocated.
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_SIDE) options.inSampleSize *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        decoded?.let { bitmap ->
            val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0f
            val upright = scaleAndRotate(bitmap, rotation)
            listOf(85, 75, 65, 55, 45, 35, 25).firstNotNullOfOrNull { quality ->
                ByteArrayOutputStream().use { out ->
                    upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray().takeIf { it.size <= MAX_BYTES }
                }
            }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        }
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/**
 * Reads how far a camera photo must turn to appear upright.
 * @param exif The photo's metadata.
 * @return Clockwise rotation in degrees.
 */
private fun exifRotation(exif: ExifInterface): Float = when (
    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> 0f
}

/**
 * Shrinks a bitmap to fit [MAX_SIDE] and turns it upright.
 * @param bitmap The decoded photo.
 * @param rotation Clockwise rotation in degrees.
 * @return The bitmap to compress.
 */
private fun scaleAndRotate(bitmap: Bitmap, rotation: Float): Bitmap {
    val scale = minOf(1f, MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
    if (scale == 1f && rotation == 0f) return bitmap
    val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}
