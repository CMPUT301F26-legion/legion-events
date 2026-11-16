/*
 * AdminRepository.kt
 *
 * Purpose: Contract for admin moderation: browse and remove campsites and profiles (US 03.01.01,
 *   03.02.01, 03.04.01, 03.05.01, 03.07.01).
 * Role: Data layer in MVC; the S19 controllers read and moderate through it.
 * Outstanding issues: Removal hides a document instead of deleting it, because the security rules
 *   never allow deleting campsites or other users' profiles.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Campsite
import com.example.legion_events.model.UserProfile

/**
 * A campsite as an admin sees it, including listings that were removed.
 * @property campsite The listing.
 * @property active False when the listing was removed and search hides it.
 */
data class ModeratedCampsite(val campsite: Campsite, val active: Boolean)

/** Reads every campsite and profile and removes those that break the app policy. Admins only. */
interface AdminRepository {
    /**
     * Lists every campsite, active or removed.
     * @return All campsites.
     */
    suspend fun getCampsites(): List<ModeratedCampsite>

    /**
     * Reads one campsite, active or removed.
     * @param campsiteId The campsite document ID.
     * @return The campsite, or null if it does not exist.
     */
    suspend fun getCampsite(campsiteId: String): ModeratedCampsite?

    /**
     * Lists every profile, including disabled ones.
     * @return All profiles.
     */
    suspend fun getProfiles(): List<UserProfile>

    /**
     * Reads one profile.
     * @param userId The profile's user ID.
     * @return The profile, or null if it does not exist.
     */
    suspend fun getProfile(userId: String): UserProfile?

    /**
     * Removes a campsite by setting `active` to false, which hides it from search (US 03.01.01).
     * @param campsiteId The campsite document ID.
     */
    suspend fun removeCampsite(campsiteId: String)

    /**
     * Removes a profile by setting `disabled` to true, which blocks the account (US 03.02.01).
     * @param userId The profile's user ID.
     */
    suspend fun removeProfile(userId: String)

    /**
     * Removes an owner who broke the app policy: disables the profile and removes all of the
     * owner's campsites in one write (US 03.07.01).
     * @param userId The owner's user ID.
     * @return How many active campsites were removed.
     */
    suspend fun removeOwner(userId: String): Int
}
