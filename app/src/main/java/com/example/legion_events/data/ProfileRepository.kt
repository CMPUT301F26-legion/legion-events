package com.example.legion_events.data

import com.example.legion_events.model.UserProfile

/** Gives profile controllers access to the signed-in user's profile without depending on Firebase. */
interface ProfileRepository {
    /**
     * Reads the signed-in user's saved profile.
     * @return The current profile values.
     * @throws IllegalStateException If no user is signed in or the profile no longer exists.
     */
    suspend fun getProfile(): UserProfile

    /**
     * Saves the user's contact details. Other profile fields are left unchanged.
     * @param name The user's display name.
     * @param email The user's contact email, or an empty string.
     * @param phone The user's contact phone number, or an empty string.
     */
    suspend fun updateContact(name: String, email: String, phone: String)

    /**
     * Saves whether the user wants to receive notifications.
     * @param enabled False opts the user out of notifications.
     */
    suspend fun setNotificationsEnabled(enabled: Boolean)

    /** Deletes the user's profile and the device identity linked to it. */
    suspend fun deleteProfile()
}
