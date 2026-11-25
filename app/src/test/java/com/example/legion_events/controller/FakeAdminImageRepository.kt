/*
 * FakeAdminImageRepository.kt
 *
 * Purpose: In-memory AdminImageRepository for the S19 Images tab tests.
 * Role: Test double; keeps photos in a list and applies removals to it.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.AdminImageRepository
import com.example.legion_events.model.Photo

/**
 * Stores photos in memory and records removals.
 * @property photos Every photo, newest first, changed by removals.
 */
class FakeAdminImageRepository(
    var photos: List<Photo> = listOf(
        Photo("p3", "jane", "campsite", "lake", "", "image/jpeg"),
        Photo("p2", "jane", "campsite", "pine", "", "image/jpeg"),
        Photo("p1", "ghost", "campsite", "gone", "", "image/png"),
    ),
) : AdminImageRepository {
    /** When set, every call throws it. */
    var failure: Exception? = null
    /** IDs passed to [removePhoto], in order. */
    val removed = mutableListOf<String>()

    override suspend fun getPhotos() = run { failure?.let { throw it }; photos }

    override suspend fun removePhoto(photoId: String) {
        failure?.let { throw it }
        removed += photoId
        photos = photos.filterNot { it.id == photoId }
    }
}
