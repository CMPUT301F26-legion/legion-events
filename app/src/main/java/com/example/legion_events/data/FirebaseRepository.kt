package com.example.legion_events.data

import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.Campsite
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Handles Firebase account and profile access, as assigned by the repository CRC card.
 * @property auth The Firebase authentication service that keeps the user's identity.
 * @property firestore The Firestore database that stores user profiles.
 */
class FirebaseRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : UserRepository, CampsiteRepository {
    /** Watches active campsites and removes the listener when the controller stops collecting. */
    override fun observeCampsites(): Flow<List<Campsite>> = callbackFlow {
        val listener = firestore.collection("campsites").whereEqualTo("active", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(IllegalStateException("Could not load campsites. Please retry.", error))
                } else {
                    try {
                        trySend(snapshot?.documents.orEmpty().map(::readCampsite).sortedBy { it.name })
                    } catch (error: Exception) {
                        close(IllegalStateException("A campsite has incomplete listing information.", error))
                    }
                }
            }
        awaitClose { listener.remove() }
    }

    /** Reads only the fields needed by the campsite list and search filters. */
    private fun readCampsite(document: DocumentSnapshot): Campsite = with(document) {
        Campsite(
            id = id,
            ownerId = getString("ownerId").orEmpty(),
            name = getString("name").orEmpty(),
            description = getString("description").orEmpty(),
            locationName = getString("locationName").orEmpty(),
            maxGuests = requireNotNull(getLong("maxGuests")).toInt(),
            pricePerNight = requireNotNull(getLong("pricePerNight")),
            amenities = (get("amenities") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            tags = (get("tags") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        )
    }

    /**
     * Opens the current account, signing in anonymously only when needed.
     *
     * Creates a missing profile without overwriting an existing one. If the rules deny
     * reading a missing profile, uses a server-enforced create-only request instead.
     *
     * @return The saved profile, or new profile defaults after a successful transaction.
     * @throws UserInitializationException If sign-in or profile setup cannot finish.
     * @throws AccountDisabledException If Firebase Authentication reports a disabled account.
     */
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

    /**
     * Reads saved document fields into a profile value without changing Firestore.
     * @param uid The authenticated user ID belonging to this document.
     * @return A profile with saved values and defaults for fields that are missing.
     */
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

    /** Shared repository access and the exact defaults used for new profiles. */
    companion object {
        /** Prevents simultaneous callers from starting separate sign-in requests. */
        private val authenticationMutex = Mutex()

        /** Repository using the app's existing default Firebase configuration. */
        val instance: FirebaseRepository by lazy {
            FirebaseRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }

        /**
         * Builds the exact fields for a new user profile, with server timestamp markers.
         * @return New-account defaults; these must never be used to reset an existing profile.
         */
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
