/*
 * AdminImageRepository.kt
 *
 * Purpose: Contract for the S19 Images tab: browse every uploaded image and remove one that breaks
 *   the app policy (US 03.06.01, 03.03.01).
 * Role: Data layer in MVC; AdminImagesController reads and removes images through it.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Photo

/** Reads every uploaded image and deletes images. Admins only. */
interface AdminImageRepository {
    /**
     * Lists every uploaded image, newest first.
     * @return All photos.
     */
    suspend fun getPhotos(): List<Photo>

    /**
     * Deletes one image (US 03.03.01).
     * @param photoId The photo document ID.
     */
    suspend fun removePhoto(photoId: String)
}
