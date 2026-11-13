/*
 * FirebaseAdminRepository.kt
 *
 * Purpose: Firestore implementation of admin moderation (US 03.01.01, 03.02.01, 03.04.01,
 *   03.05.01, 03.07.01).
 * Role: Data layer in MVC; reads `campsites` and `users`, and changes only the fields the admin
 *   security rules allow: `active` on a campsite, `disabled` on a profile, and `updatedAt`.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Reads and moderates every campsite and profile. Firestore rejects these calls for non-admins.
 * @property firestore The app's Firestore database.
 */
class FirebaseAdminRepository(private val firestore: FirebaseFirestore) : AdminRepository {
    override suspend fun getCampsites(): List<ModeratedCampsite> =
        firestore.collection(CAMPSITES).get().await().documents
            .map { ModeratedCampsite(it.toOwnedCampsite(), it.getBoolean("active") ?: false) }

    override suspend fun getCampsite(campsiteId: String): ModeratedCampsite? {
        val document = firestore.collection(CAMPSITES).document(campsiteId).get().await()
        return if (document.exists()) ModeratedCampsite(document.toOwnedCampsite(), document.getBoolean("active") ?: false)
        else null
    }

    override suspend fun getProfiles() =
        firestore.collection(USERS).get().await().documents.map { it.toUserProfile(it.id) }

    override suspend fun getProfile(userId: String) =
        firestore.collection(USERS).document(userId).get().await().takeIf { it.exists() }?.toUserProfile(userId)

    override suspend fun removeCampsite(campsiteId: String) {
        firestore.collection(CAMPSITES).document(campsiteId).update(removedCampsite()).await()
    }

    override suspend fun removeProfile(userId: String) {
        firestore.collection(USERS).document(userId).update(disabledProfile()).await()
    }

    override suspend fun removeOwner(userId: String): Int {
        val listings = firestore.collection(CAMPSITES).whereEqualTo("ownerId", userId).get().await().documents
            .filter { it.getBoolean("active") == true }
        // One batch, so the owner is never disabled while their listings stay visible.
        val batch = firestore.batch()
        batch.update(firestore.collection(USERS).document(userId), disabledProfile())
        listings.forEach { batch.update(it.reference, removedCampsite()) }
        batch.commit().await()
        return listings.size
    }

    companion object {
        private const val CAMPSITES = "campsites"
        private const val USERS = "users"

        /** The only campsite fields the admin rule lets an admin change. */
        private fun removedCampsite() = mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp())

        /** The only profile fields the admin rule lets an admin change. */
        private fun disabledProfile() = mapOf("disabled" to true, "updatedAt" to FieldValue.serverTimestamp())

        /** Repository for the app's default Firebase project. */
        val instance: FirebaseAdminRepository by lazy { FirebaseAdminRepository(FirebaseFirestore.getInstance()) }
    }
}
