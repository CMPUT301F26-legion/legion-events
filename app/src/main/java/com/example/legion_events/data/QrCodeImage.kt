/*
 * QrCodeImage.kt
 *
 * Purpose: Draws a campsite's QR code as a bitmap for S14 (US 02.02.01).
 * Role: Data layer helper used by OwnerCampsiteScreen.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.OutputStream

/**
 * Draws a QR code as a black-on-white bitmap, which scanners read in light and dark mode.
 * Call from a background dispatcher.
 * @param text The text to encode.
 * @param size The width and height in pixels.
 * @return The QR code image.
 */
internal fun createQrBitmap(text: String, size: Int = 768): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/**
 * Writes a QR code as a PNG, for example to a file the user chose with Download.
 * @param bitmap The QR code image.
 * @param out Where the PNG is written; the caller closes it.
 */
internal fun writeQrPng(bitmap: Bitmap, out: OutputStream) {
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not write the QR code." }
}

/**
 * Saves a QR code in the app cache so the Android share sheet can read it.
 * Call from a background dispatcher.
 * @param context Supplies the cache directory and the app's file provider.
 * @param bitmap The QR code image.
 * @param campsiteId Names the file so codes for different campsites do not overwrite each other mid-share.
 * @return A temporary read-only content URI for the PNG.
 */
internal fun cacheQrPng(context: Context, bitmap: Bitmap, campsiteId: String): Uri {
    val folder = File(context.cacheDir, "qr").apply { mkdirs() }
    val file = File(folder, "campsite-${campsiteId.filter(Char::isLetterOrDigit)}.png")
    file.outputStream().use { writeQrPng(bitmap, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
