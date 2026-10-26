package com.example.legion_events.model

import com.google.firebase.Timestamp

/** The document ID is the authenticated UID; it is not an extra Firestore field. */
data class UserProfile(
    val uid: String,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val role: String = "user",
    val disabled: Boolean = false,
    val photoId: String? = null,
    val fcmToken: String? = null,
    val notificationsEnabled: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
