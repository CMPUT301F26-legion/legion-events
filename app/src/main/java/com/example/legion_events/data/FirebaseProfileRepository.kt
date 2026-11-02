package com.example.legion_events.data

import com.example.legion_events.model.UserProfile
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
     * Deletes the profile document, then the anonymous account, so the next launch gets a new identity.
     * If Firebase refuses to delete the account, signs out instead so the old identity is not reused.
     */
    override suspend fun deleteProfile() {
        val user = checkNotNull(auth.currentUser) { "No user is signed in." }
        firestore.collection("users").document(user.uid).delete().await()
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
