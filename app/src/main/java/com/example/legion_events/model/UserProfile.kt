package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * Holds the user's saved account details and preferences.
 *
 * @constructor Creates a profile value. Defaults describe a new user; this does not write to Firebase.
 * @property uid The Firebase Auth user ID, also used as the Firestore document ID rather than an extra field.
 * @property name The user's name, or an empty string until supplied.
 * @property email The user's contact email, or an empty string until supplied.
 * @property phone The user's contact phone number, or an empty string until supplied.
 * @property role The account role, `user` or `admin`. Listing ownership is stored on each campsite.
 * @property disabled Whether the account is blocked from entering Home.
 * @property photoId The profile photo's document ID, or null when there is no photo.
 * @property fcmToken The push notification token, or null when one has not been saved.
 * @property notificationsEnabled Whether the user wants notifications.
 * @property createdAt The server time when the profile was created, or null before that time is loaded.
 * @property updatedAt The server time when the profile last changed, or null before that time is loaded.
 */
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
