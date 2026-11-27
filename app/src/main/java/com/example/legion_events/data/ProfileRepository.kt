/*
 * ProfileRepository.kt
 *
 * Purpose: Interface for reading, updating, and deleting the signed-in user's profile (US
 *   01.07.03, 01.08.01, 01.08.02, 01.10.01), including profile images.
 * Role: Data layer (MVC model access). Lets ProfileController work without depending on Firebase,
 *   so it can be tested with a fake.
 * Outstanding issues: deleteProfile() does not yet remove the user's waitlist entries or
 *   reservations; that needs WaitlistEntry (Person 5).
 */
package com.example.legion_events.data

import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.Photo

/** Gives profile controllers access to the signed-in user's profile without depending on Firebase. */
interface ProfileRepository {
    /** Reads the signed-in user's current profile image, or null if absent or removed by moderation. */
    suspend fun getPhoto(): Photo?

    /** Atomically replaces the expected previous image; the stable new ID makes uncertain saves retryable. */
    suspend fun setPhoto(photoId: String, jpegBase64: String, previousPhotoId: String?): Photo

    /** Clears the expected profile image and deletes its owned photo without removing a newer replacement. */
    suspend fun removePhoto(photoId: String)

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
