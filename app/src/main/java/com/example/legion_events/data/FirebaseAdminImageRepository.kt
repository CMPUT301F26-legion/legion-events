/*
 * FirebaseAdminImageRepository.kt
 *
 * Purpose: Firestore implementation of the S19 Images tab (US 03.06.01, 03.03.01).
 * Role: Data layer in MVC; reads every `photos/{id}` document and deletes one, which the security
 *   rules allow for the photo's owner or an admin.
 * Outstanding issues: A campsite whose cover was removed keeps its old `photoId`, because the admin
 *   rule cannot change that field; the gallery and cards skip the missing photo.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Photo
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Reads and deletes uploaded images. Firestore rejects deletes from anyone but the owner or an admin.
 * @property firestore The app's Firestore database.
 */
class FirebaseAdminImageRepository(private val firestore: FirebaseFirestore) : AdminImageRepository {
    override suspend fun getPhotos(): List<Photo> =
        firestore.collection(PHOTOS).get().await().documents
            // Sorted here, not in the query, so a photo without createdAt is still listed.
            .sortedByDescending { it.getTimestamp("createdAt") ?: Timestamp(0, 0) }
            .map { document ->
                Photo(
                    id = document.id,
                    ownerId = document.getString("ownerId").orEmpty(),
                    targetType = document.getString("targetType").orEmpty(),
                    targetId = document.getString("targetId").orEmpty(),
                    imageBase64 = document.getString("imageBase64").orEmpty(),
                    mimeType = document.getString("mimeType").orEmpty(),
                )
            }

    override suspend fun removePhoto(photoId: String) {
        firestore.collection(PHOTOS).document(photoId).delete().await()
    }

    companion object {
        private const val PHOTOS = "photos"

        /** Repository for the app's default Firebase project. */
        val instance: FirebaseAdminImageRepository by lazy { FirebaseAdminImageRepository(FirebaseFirestore.getInstance()) }
    }
}
