package com.example.legion_events.model

/**
 * A compressed image stored in a Firestore photo document.
 * @property id Photo document ID.
 * @property ownerId User who owns the photo.
 * @property targetType Kind of item pictured, such as campsite or user.
 * @property targetId ID of the pictured item.
 * @property imageBase64 Compressed image bytes encoded as Base64.
 * @property mimeType Image format, such as image/jpeg or image/webp.
 */
data class Photo(
    val id: String,
    val ownerId: String,
    val targetType: String,
    val targetId: String,
    val imageBase64: String,
    val mimeType: String,
)
