package com.example.legion_events.data

import com.example.legion_events.model.Lottery
import com.example.legion_events.model.MAX_WAITLIST_LIMIT
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Reads and writes lotteries at `lotteries/{id}`, using exactly the fields the security rules allow.
 * @property auth Identifies the signed-in owner.
 * @property firestore The app's Firestore database.
 */
class FirebaseLotteryRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : LotteryRepository {
    /** The lotteries collection. */
    private val lotteries: CollectionReference get() = firestore.collection("lotteries")

    /**
     * Saves a new open lottery owned by the signed-in user.
     * The passed ownerId, entrantCount, status, and winnersDrawn are ignored; the rules require their starting values.
     */
    override suspend fun createLottery(lottery: Lottery): String {
        val ownerId = checkNotNull(auth.currentUser) { "No user is signed in." }.uid
        val document = lotteries.document()
        document.set(
            lottery.editableFields() + mapOf(
                "ownerId" to ownerId,
                "entrantCount" to 0,
                "status" to "open",
                "winnersDrawn" to false,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        return document.id
    }

    /** Updates only owner-editable fields; the rules allow this before registration opens and while nobody has joined. */
    override suspend fun updateLottery(lottery: Lottery) {
        lotteries.document(lottery.id).update(lottery.editableFields()).await()
    }

    override suspend fun getLottery(id: String): Lottery {
        val document = lotteries.document(id).get().await()
        check(document.exists()) { "This lottery no longer exists." }
        return document.toLottery()
    }

    override suspend fun getLotteriesForOwner(ownerId: String): List<Lottery> =
        lotteries.whereEqualTo("ownerId", ownerId).get().await().documents.map { it.toLottery() }

    /** Fields the owner may set when creating or editing (US 02.04.01–02.04.07). */
    private fun Lottery.editableFields(): Map<String, Any> = mapOf(
        "campsiteIds" to campsiteIds,
        "registrationStartAt" to registrationStartAt,
        "registrationEndAt" to registrationEndAt,
        "bookingStartAt" to bookingStartAt,
        "bookingEndAt" to bookingEndAt,
        "winnerCount" to winnerCount,
        "waitlistLimit" to waitlistLimit,
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    /** Shared repository access. */
    companion object {
        /** Repository using the app's default Firebase configuration. */
        val instance: FirebaseLotteryRepository by lazy {
            FirebaseLotteryRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }
    }
}

/**
 * Reads a lottery document into a [Lottery]. Other features reading lotteries can reuse this.
 * @throws IllegalArgumentException If a required timestamp or count is missing.
 */
fun DocumentSnapshot.toLottery() = Lottery(
    id = id,
    ownerId = getString("ownerId").orEmpty(),
    campsiteIds = (get("campsiteIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    registrationStartAt = requireNotNull(getTimestamp("registrationStartAt")),
    registrationEndAt = requireNotNull(getTimestamp("registrationEndAt")),
    bookingStartAt = requireNotNull(getTimestamp("bookingStartAt")),
    bookingEndAt = requireNotNull(getTimestamp("bookingEndAt")),
    winnerCount = requireNotNull(getLong("winnerCount")).toInt(),
    waitlistLimit = getLong("waitlistLimit")?.toInt() ?: MAX_WAITLIST_LIMIT,
    entrantCount = getLong("entrantCount")?.toInt() ?: 0,
    status = getString("status") ?: "open",
    winnersDrawn = getBoolean("winnersDrawn") ?: false,
)
