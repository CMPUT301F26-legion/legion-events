package com.example.legion_events.data

import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo

/** Gives owner screens (S12 to S14) access to the signed-in user's own listings without depending on Firebase. */
interface OwnerCampsiteRepository {
    /**
     * Reads every campsite the signed-in user owns, active or not.
     * @return The user's campsites sorted by name.
     */
    suspend fun getMyCampsites(): List<Campsite>

    /**
     * Reads one campsite the signed-in user owns, active or not.
     * @param campsiteId The campsite document ID.
     * @return The campsite, or null if it does not exist or belongs to someone else.
     */
    suspend fun getMyCampsite(campsiteId: String): Campsite?

    /**
     * Reads the signed-in user's lotteries, used to show each campsite's lottery state.
     * @return The user's lotteries; unreadable documents are left out.
     */
    suspend fun getMyLotteries(): List<Lottery>

    /**
     * Reads photos by document ID.
     * @param photoIds Photo document IDs.
     * @return The photos that exist; missing ones are left out.
     */
    suspend fun getPhotos(photoIds: List<String>): List<Photo>

    /**
     * Creates or updates a campsite owned by the signed-in user.
     *
     * A blank [Campsite.id] creates a new active listing. Otherwise the existing listing's
     * editable fields are replaced; its owner, ratings, and active flag are kept.
     *
     * @param campsite The listing values, including coordinates. Its ownerId, photoId, and ratings are ignored.
     * @param coverJpegBase64 A new Base64 JPEG cover photo, or null to keep the current cover.
     * @return The campsite document ID.
     * @throws IllegalStateException If the campsite to update belongs to someone else.
     * @throws CoverPhotoNotSavedException If a new campsite was created but its photo was not saved.
     */
    suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?): String
}

/**
 * A new campsite was saved, but its cover photo was not. Saving again with this ID adds the photo.
 * @property campsiteId The saved campsite's document ID.
 */
class CoverPhotoNotSavedException(val campsiteId: String, cause: Throwable) :
    Exception("The campsite was saved, but its photo was not.", cause)
