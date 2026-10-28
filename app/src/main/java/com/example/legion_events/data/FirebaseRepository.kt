package com.example.legion_events.data

import com.example.legion_events.model.UserProfile
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** CRC: the single place for Firebase identity and Firestore user access. */
class FirebaseRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : UserRepository {
    override suspend fun initializeUser(): UserProfile {
        val user = try {
            // A cancelled screen must not release the lock while sign-in is still running.
            authenticationMutex.withLock {
                withContext(NonCancellable) {
                    auth.currentUser ?: auth.signInAnonymously().await().user
                    ?: throw UserInitializationException("Sign-in returned no account. Please retry.")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FirebaseAuthInvalidUserException) {
            if (error.errorCode == "ERROR_USER_DISABLED") throw AccountDisabledException()
            throw UserInitializationException("Your account could not be opened. Please retry.", error)
        } catch (error: FirebaseNetworkException) {
            throw UserInitializationException("Could not connect to sign-in. Check your connection and retry.", error)
        } catch (error: Exception) {
            throw UserInitializationException("Anonymous sign-in failed. Please retry. If this continues, contact the app administrator.", error)
        }

        val document = firestore.collection("users").document(user.uid)
        return try {
            try {
                // Transactions retry on contention. Existing profiles are never written.
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(document)
                    if (snapshot.exists()) {
                        snapshot.toUserProfile(user.uid)
                    } else {
                        transaction.set(document, newProfileFields())
                        UserProfile(uid = user.uid)
                    }
                }.await()
            } catch (error: FirebaseFirestoreException) {
                if (error.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw error
                // The deployed policy allows creating users, but denies reading a missing
                // profile. A create-only precondition makes that first launch safe too.
                val projectId = firestore.app.options.projectId
                    ?: throw UserInitializationException("Firebase project configuration is missing.")
                FirestoreProfileCreator().createIfAbsent(user, projectId, newProfileFields())
                val snapshot = document.get(Source.SERVER).await()
                if (!snapshot.exists()) {
                    throw UserInitializationException("Your profile is not available yet. Please retry.")
                }
                snapshot.toUserProfile(user.uid)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: UserInitializationException) {
            throw error
        } catch (error: FirebaseFirestoreException) {
            val message = when (error.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "Your profile could not be accessed. Retry, or contact the app administrator if this continues."
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "Could not connect to your profile. Check your connection and retry."
                else -> "Your profile could not be initialized. Please retry."
            }
            throw UserInitializationException(message, error)
        } catch (error: Exception) {
            throw UserInitializationException("Your profile could not be loaded. Please retry.", error)
        }
    }

    private fun DocumentSnapshot.toUserProfile(uid: String) = UserProfile(
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

    companion object {
        // Shared even if multiple repository instances initialize the same FirebaseAuth.
        private val authenticationMutex = Mutex()

        val instance: FirebaseRepository by lazy {
            FirebaseRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }

        internal fun newProfileFields(): Map<String, Any?> = mapOf(
            "name" to "",
            "email" to "",
            "phone" to "",
            "role" to "user",
            "disabled" to false,
            "photoId" to null,
            "fcmToken" to null,
            "notificationsEnabled" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
    }
}
