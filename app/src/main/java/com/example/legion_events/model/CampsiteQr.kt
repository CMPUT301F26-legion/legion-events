package com.example.legion_events.model

/**
 * The text stored in a campsite QR code (S14) and read by the QR scanner (S05).
 *
 * The code holds only the campsite's document ID behind a fixed prefix, so a scanner
 * can tell campsite codes apart from unrelated QR codes.
 */
object CampsiteQr {
    /** Prefix placed before the campsite document ID. */
    const val PREFIX = "legionevents://campsite/"

    /**
     * Builds the QR text for a campsite.
     * @param campsiteId The campsite's Firestore document ID.
     * @return The text to encode in the QR code.
     */
    fun encode(campsiteId: String): String = PREFIX + campsiteId

    /**
     * Reads a campsite ID from scanned QR text.
     * @param text The scanned text.
     * @return The campsite document ID, or null if this is not a campsite code.
     */
    fun decode(text: String): String? =
        text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
            ?.takeIf { it.isNotEmpty() && '/' !in it }
}
