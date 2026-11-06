/* Firebase access for the signed-in entrant's booking offers and decline actions. */
package com.example.legion_events.data

import com.example.legion_events.model.BookingOffer
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Reads and declines offers at offers/{id} for the signed-in entrant.
 *
 * Offer creation belongs to the draw worker. Firestore rules must also enforce ownership,
 * permitted status changes, and deadlines using server time.
 *
 * @property auth Supplies the current Firebase Auth identity.
 * @property firestore Supplies offer documents and atomic transactions.
 * @property now Supplies device time for the local deadline check; injectable for unit tests.
 */
class FirebaseOfferRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val now: () -> Timestamp = { Timestamp.now() },
) : OfferRepository {
    /**
     * Reads an offer after checking that an account is signed in.
     * @param offerId One document ID in the offers collection.
     * @return The owned offer, or null when Firestore reports that it does not exist.
     * @throws IllegalStateException If signed out or the saved offer is incomplete or invalid.
     * @throws SecurityException If the offer belongs to another user or Firestore denies access.
     */
    override suspend fun getOffer(offerId: String): BookingOffer? {
        val userId = currentUserId()
        return withOfferAccess {
            val document = offerDocument(offerId).get().await()
            if (document.exists()) document.toOwnedOffer(userId) else null
        }
    }

    /**
     * Queries offers by the authenticated UID, including used, declined, and expired offers.
     * @return Owned offers, or an empty list when none match.
     * @throws IllegalStateException If signed out or any saved offer is incomplete or invalid.
     * @throws SecurityException If Firestore denies access or a result has another owner.
     */
    override suspend fun getOffersForCurrentUser(): List<BookingOffer> {
        val userId = currentUserId()
        return withOfferAccess {
            firestore.collection(COLLECTION).whereEqualTo("userId", userId).get().await()
                .documents.map { it.toOwnedOffer(userId) }
        }
    }

    /**
     * Atomically declines an owned, open offer whose response deadline has not passed.
     *
     * Every transaction attempt checks the current document again. Already declined offers
     * require no write. After commit, a server read retrieves the saved server timestamp.
     * A failed follow-up read can be retried without declining or updating the offer twice.
     *
     * @param offerId One document ID in the offers collection.
     * @return The saved declined offer, including its server-provided updatedAt.
     * @throws IllegalStateException If signed out, missing, invalid, used, or expired.
     * @throws SecurityException If the offer belongs to another user or Firestore denies access.
     */
    override suspend fun declineOffer(offerId: String): BookingOffer {
        val userId = currentUserId()
        val reference = offerDocument(offerId)
        return withOfferAccess {
            firestore.runTransaction<Unit> { transaction ->
                val document = transaction.get(reference)
                check(document.exists()) { "Offer does not exist." }
                val offer = document.toOwnedOffer(userId)

                if (offer.status == BookingOffer.STATUS_DECLINED) return@runTransaction
                check(offer.status == BookingOffer.STATUS_OPEN) { "This offer cannot be declined." }
                val expiresAt = checkNotNull(offer.expiresAt) { "This offer has no response deadline." }
                check(expiresAt > now()) { "This offer has expired." }

                transaction.update(
                    reference,
                    mapOf(
                        "response" to "decline",
                        "respondedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }.await()

            val saved = reference.get(Source.SERVER).await()
            check(saved.exists()) { "Offer no longer exists." }
            saved.toOwnedOffer(userId).also {
                check(it.status == BookingOffer.STATUS_DECLINED) { "The offer changed. Please reload it." }
            }
        }
    }

    /** Returns the signed-in UID before starting database work, or fails when signed out. */
    private fun currentUserId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    /** Resolves a single offer ID, rejecting blank IDs and paths into other collections. */
    private fun offerDocument(offerId: String): DocumentReference {
        require(offerId.isNotBlank() && '/' !in offerId) { "A single offer document ID is required." }
        return firestore.collection(COLLECTION).document(offerId)
    }

    /** Maps a saved offer only after confirming its owner and required fields. */
    private fun DocumentSnapshot.toOwnedOffer(userId: String): BookingOffer {
        val ownerId = requiredString("userId")
        if (ownerId != userId) throw SecurityException("This offer belongs to another user.")
        val status = when (val storedStatus = requiredString("status")) {
            "pending" -> when (get("response")) {
                null -> BookingOffer.STATUS_OPEN
                "decline" -> BookingOffer.STATUS_DECLINED
                else -> error("Offer has an invalid response.")
            }
            BookingOffer.STATUS_USED, BookingOffer.STATUS_DECLINED, BookingOffer.STATUS_EXPIRED -> storedStatus
            else -> error("Offer has an invalid status.")
        }
        return BookingOffer(
            id = id,
            lotteryId = requiredString("lotteryId"),
            userId = ownerId,
            status = status,
            expiresAt = optionalTimestamp("expiresAt"),
            createdAt = optionalTimestamp("createdAt"),
            updatedAt = optionalTimestamp("updatedAt"),
        )
    }

    /** Reads required text without turning missing or blank fields into valid offer data. */
    private fun DocumentSnapshot.requiredString(field: String): String =
        checkNotNull((get(field) as? String)?.takeIf { it.isNotBlank() }) {
            "Offer has a missing or invalid $field."
        }

    /** Accepts absent optional timestamps while rejecting fields stored with another type. */
    private fun DocumentSnapshot.optionalTimestamp(field: String): Timestamp? {
        val value = get(field)
        check(value == null || value is Timestamp) { "Offer has an invalid $field." }
        return value
    }

    /** Converts Firestore permission denials to the repository's access error; other failures propagate. */
    private suspend fun <T> withOfferAccess(operation: suspend () -> T): T = try {
        operation()
    } catch (error: FirebaseFirestoreException) {
        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            throw SecurityException("Your account cannot access this offer.", error)
        }
        throw error
    }

    /** Shared repository access and stored field values. */
    companion object {
        private const val COLLECTION = "offers"

        /** Repository using the app's existing default Firebase configuration. */
        val instance: FirebaseOfferRepository by lazy {
            FirebaseOfferRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }
    }
}
