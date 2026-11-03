package com.example.legion_events.data

import com.example.legion_events.model.Lottery
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Reads and writes lotteries at `lotteries/{id}`.
 * @property auth Identifies the signed-in owner.
 * @property firestore The app's Firestore database.
 */
class FirebaseLotteryRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : LotteryRepository {
    /** The lotteries collection. */
    private val lotteries: CollectionReference get() = firestore.collection("lotteries")

    /** Saves a new lottery owned by the signed-in user; the passed ownerId and drawn are ignored. */
    override suspend fun createLottery(lottery: Lottery): String {
        val ownerId = checkNotNull(auth.currentUser) { "No user is signed in." }.uid
        val document = lotteries.document()
        val fields = lottery.editableFields() + mapOf(
            "ownerId" to ownerId,
            "drawn" to false,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        document.set(lottery.waitlistCapacity?.let { fields + ("waitlistCapacity" to it) } ?: fields).await()
        return document.id
    }

    /** Updates only owner-editable fields, so `ownerId` and the draw's `drawn` flag are never overwritten. */
    override suspend fun updateLottery(lottery: Lottery) {
        val capacity: Any = lottery.waitlistCapacity ?: FieldValue.delete()
        lotteries.document(lottery.id).update(lottery.editableFields() + ("waitlistCapacity" to capacity)).await()
    }

    override suspend fun getLottery(id: String): Lottery {
        val document = lotteries.document(id).get().await()
        check(document.exists()) { "This lottery no longer exists." }
        return document.toLottery()
    }

    override suspend fun getLotteriesForOwner(ownerId: String): List<Lottery> =
        lotteries.whereEqualTo("ownerId", ownerId).get().await().documents.map { it.toLottery() }

    /** Fields the owner may change when creating or editing (US 02.04.01–02.04.06). */
    private fun Lottery.editableFields(): Map<String, Any> = mapOf(
        "title" to title,
        "campsiteIds" to campsiteIds,
        "registrationStart" to registrationStart,
        "registrationEnd" to registrationEnd,
        "bookingStart" to bookingStart,
        "bookingEnd" to bookingEnd,
        "numWinners" to numWinners,
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
 * Reads a lottery document into a [Lottery].
 * @throws IllegalArgumentException If a required field is missing.
 */
internal fun DocumentSnapshot.toLottery() = Lottery(
    id = id,
    ownerId = getString("ownerId").orEmpty(),
    title = getString("title").orEmpty(),
    campsiteIds = (get("campsiteIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    registrationStart = requireNotNull(getTimestamp("registrationStart")),
    registrationEnd = requireNotNull(getTimestamp("registrationEnd")),
    bookingStart = getString("bookingStart").orEmpty(),
    bookingEnd = getString("bookingEnd").orEmpty(),
    numWinners = requireNotNull(getLong("numWinners")).toInt(),
    waitlistCapacity = getLong("waitlistCapacity")?.toInt(),
    drawn = getBoolean("drawn") ?: false,
)