/*
 * FirebaseProfileRepository.kt
 *
 * Purpose: Firebase implementation of ProfileRepository, backed by users/{uid} and the anonymous
 *   Firebase Auth account.
 * Role: Data layer (MVC model access). The only place profile edits and deletion reach Firestore.
 * Outstanding issues: Waitlist entries and reservations are left behind on delete.
 *   initializeUser() still lives in FirebaseRepository and
 *   should move here.
 */
package com.example.legion_events.data

import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.Photo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Reads, updates, and deletes the signed-in user's `users/{uid}` profile.
 * @property auth The Firebase authentication service that holds the device identity.
 * @property firestore The app's Firestore database.
 */
class FirebaseProfileRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : ProfileRepository {
    /** Resolves only a photo owned by this profile, checking identity again after each read. */
    override suspend fun getPhoto(): Photo? {
        val uid = checkNotNull(auth.currentUser).uid
        val profile = firestore.collection("users").document(uid).get().await()
        checkIdentity(uid)
        val id = profile.get("photoId") as? String ?: return null
        require(id.isNotBlank() && '/' !in id) { "Invalid profile photo ID." }
        val photo = firestore.collection("photos").document(id).get().await()
        checkIdentity(uid)
        return photo.ownedProfilePhoto(uid)
    }

    /** Creates an immutable photo and updates only the profile pointer and server time in one transaction. */
    override suspend fun setPhoto(photoId: String, jpegBase64: String, previousPhotoId: String?): Photo {
        require(photoId.isNotBlank() && '/' !in photoId) { "Invalid photo ID." }
        require(jpegBase64.isNotBlank() && jpegBase64.length <= 280_000) { "Choose a smaller image." }
        val uid = checkNotNull(auth.currentUser).uid
        val profileRef = firestore.collection("users").document(uid)
        val photoRef = firestore.collection("photos").document(photoId)
        firestore.runTransaction<Unit> { transaction ->
            checkIdentity(uid)
            val profile = transaction.get(profileRef)
            check(profile.exists() && profile.get("disabled") != true) { "Profile is unavailable." }
            val oldId = profile.get("photoId") as? String
            val newPhoto = transaction.get(photoRef)
            if (newPhoto.exists()) {
                check(oldId == photoId && newPhoto.ownedProfilePhoto(uid)?.imageBase64 == jpegBase64) { "Your photo changed. Reload it first." }
                return@runTransaction
            }
            val old = oldId?.let {
                require(it.isNotBlank() && '/' !in it) { "Invalid existing photo ID." }
                transaction.get(firestore.collection("photos").document(it))
            }
            check(oldId == previousPhotoId || (previousPhotoId == null && old != null && !old.exists())) {
                "Your photo changed. Reload it first."
            }
            checkIdentity(uid)
            transaction.set(photoRef, mapOf("ownerId" to uid, "targetType" to "user", "targetId" to uid,
                "imageBase64" to jpegBase64, "mimeType" to "image/jpeg",
                "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
            transaction.update(profileRef, mapOf("photoId" to photoId, "updatedAt" to FieldValue.serverTimestamp()))
            if (old?.ownedProfilePhoto(uid) != null) transaction.delete(old.reference)
        }.await()
        checkIdentity(uid)
        return Photo(photoId, uid, "user", uid, jpegBase64, "image/jpeg")
    }

    /** Removes the displayed image only; a stale retry cannot delete a replacement from another session. */
    override suspend fun removePhoto(photoId: String) {
        require(photoId.isNotBlank() && '/' !in photoId) { "Invalid photo ID." }
        val uid = checkNotNull(auth.currentUser).uid
        val profileRef = firestore.collection("users").document(uid)
        firestore.runTransaction<Unit> { transaction ->
            checkIdentity(uid)
            val profile = transaction.get(profileRef)
            check(profile.exists() && profile.get("disabled") != true) { "Profile is unavailable." }
            val current = profile.get("photoId") as? String ?: return@runTransaction
            check(current == photoId) { "Your photo changed. Reload it first." }
            val photo = transaction.get(firestore.collection("photos").document(photoId))
            checkIdentity(uid)
            transaction.update(profileRef, mapOf("photoId" to null, "updatedAt" to FieldValue.serverTimestamp()))
            if (photo.ownedProfilePhoto(uid) != null) transaction.delete(photo.reference)
        }.await()
        checkIdentity(uid)
    }

    /** Refuses to return another identity's image after an account change. */
    private fun checkIdentity(uid: String) { check(auth.currentUser?.uid == uid) { "The signed-in account changed." } }

    /** Validates ownership and target before displaying or deleting a photo. */
    private fun DocumentSnapshot.ownedProfilePhoto(uid: String): Photo? {
        if (!exists() || get("ownerId") != uid || get("targetType") != "user" || get("targetId") != uid) return null
        return Photo(id, uid, "user", uid, get("imageBase64") as? String ?: "", get("mimeType") as? String ?: "")
    }

    /** Reads the profile; onboarding has already created it. */
    override suspend fun getProfile(): UserProfile {
        val document = profileDocument().get().await()
        check(document.exists()) { "Your profile no longer exists." }
        return document.toUserProfile(document.id)
    }

    /** Updates only the contact fields so admin-managed fields are never overwritten. */
    override suspend fun updateContact(name: String, email: String, phone: String) {
        profileDocument().update(
            mapOf("name" to name, "email" to email, "phone" to phone, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    /** Saves the opt-out flag that notification senders check before sending. */
    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        profileDocument().update(
            mapOf("notificationsEnabled" to enabled, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    /**
     * Atomically deletes the profile and its current owned profile photo, then the anonymous account.
     * Reading the pointer in the transaction handles a photo replacement racing with deletion.
     * If Firebase refuses to delete the account, signs out instead so the old identity is not reused.
     */
    override suspend fun deleteProfile() {
        val user = checkNotNull(auth.currentUser) { "No user is signed in." }
        val profileRef = firestore.collection("users").document(user.uid)
        firestore.runTransaction<Unit> { transaction ->
            checkIdentity(user.uid)
            val profile = transaction.get(profileRef)
            check(profile.exists()) { "Your profile no longer exists." }
            val photo = (profile.get("photoId") as? String)?.let { id ->
                require(id.isNotBlank() && '/' !in id) { "Invalid profile photo ID." }
                transaction.get(firestore.collection("photos").document(id))
            }
            checkIdentity(user.uid)
            if (photo?.ownedProfilePhoto(user.uid) != null) transaction.delete(photo.reference)
            transaction.delete(profileRef)
            Unit
        }.await()
        checkIdentity(user.uid)
        try {
            user.delete().await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            auth.signOut()
        }
    }

    /**
     * Finds the signed-in user's profile document.
     * @return The `users/{uid}` reference.
     * @throws IllegalStateException If no user is signed in.
     */
    private fun profileDocument(): DocumentReference =
        firestore.collection("users").document(checkNotNull(auth.currentUser) { "No user is signed in." }.uid)

    /** Shared repository access. */
    companion object {
        /** Repository using the app's existing default Firebase configuration. */
        val instance: FirebaseProfileRepository by lazy {
            FirebaseProfileRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }
    }
}

/**
 * Reads saved document fields into a profile value without changing Firestore.
 * @param uid The authenticated user ID belonging to this document.
 * @return A profile with saved values and defaults for fields that are missing.
 */
internal fun DocumentSnapshot.toUserProfile(uid: String) = UserProfile(
    uid = uid,
    name = getString("name") ?: "",
    email = getString("email") ?: "",
    phone = getString("phone") ?: "",
    role = getString("role") ?: "user",
    disabled = getBoolean("disabled") ?: false,
    photoId = getString("photoId"),
    fcmToken = getString("fcmToken"),
    notificationsEnabled = getBoolean("notificationsEnabled") ?: true,
    createdAt = getTimestamp("createdAt"),
    updatedAt = getTimestamp("updatedAt"),
)
