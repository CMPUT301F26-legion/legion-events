package com.example.legion_events.data

import android.util.Log
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Reads and writes the signed-in user's campsites at `campsites/{id}` and their cover photos at `photos/{id}`.
 * @property auth Identifies the signed-in owner.
 * @property firestore The app's Firestore database.
 */
class FirebaseOwnerCampsiteRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : OwnerCampsiteRepository {
    /** Lists the owner's campsites, including inactive ones that search hides. */
    override suspend fun getMyCampsites(): List<Campsite> =
        firestore.collection("campsites").whereEqualTo("ownerId", ownerId()).get().await()
            .documents.map { it.toOwnedCampsite() }.sortedBy { it.name.lowercase() }

    /** Reads a campsite only when the signed-in user owns it. */
    override suspend fun getMyCampsite(campsiteId: String): Campsite? {
        val document = firestore.collection("campsites").document(campsiteId).get().await()
        return if (document.exists() && document.getString("ownerId") == ownerId()) document.toOwnedCampsite() else null
    }

    /** Lists the owner's lotteries, skipping documents with missing required fields. */
    override suspend fun getMyLotteries(): List<Lottery> =
        firestore.collection("lotteries").whereEqualTo("ownerId", ownerId()).get().await()
            .documents.mapNotNull { document -> runCatching { document.toOwnerLottery() }.getOrNull() }

    /** Reads each photo in parallel; there is at most one cover per campsite. */
    override suspend fun getPhotos(photoIds: List<String>): List<Photo> = coroutineScope {
        photoIds.distinct().map { id ->
            async { firestore.collection("photos").document(id).get().await() }
        }.awaitAll().filter { it.exists() }.map { document ->
            Photo(
                id = document.id,
                ownerId = document.getString("ownerId").orEmpty(),
                targetType = document.getString("targetType").orEmpty(),
                targetId = document.getString("targetId").orEmpty(),
                imageBase64 = document.getString("imageBase64").orEmpty(),
                mimeType = document.getString("mimeType").orEmpty(),
            )
        }
    }

    /**
     * Writes the campsite, then any new cover photo, in the shape the Firestore rules require.
     *
     * The rules check a photo's campsite owner with `get()`, which cannot see a campsite created
     * in the same batch. A new campsite is therefore written first, and its photo second.
     */
    override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?): String {
        val ownerId = ownerId()
        val creating = campsite.id.isBlank()
        val campsites = firestore.collection("campsites")
        val document = if (creating) campsites.document() else campsites.document(campsite.id)
        if (!creating) {
            check(document.get().await().getString("ownerId") == ownerId) { "You can only edit your own campsites." }
        }
        val fields = mutableMapOf<String, Any?>(
            "name" to campsite.name,
            "locationName" to campsite.locationName,
            "description" to campsite.description,
            "maxGuests" to campsite.maxGuests.toLong(),
            "pricePerNight" to campsite.pricePerNight,
            "amenities" to campsite.amenities,
            "tags" to campsite.tags,
            // The rules require coordinates and a single rules string, one rule per line.
            "location" to checkNotNull(campsite.location) { "Campsite coordinates are required." },
            "rules" to campsite.rules.joinToString("\n"),
            "cancellationPolicy" to campsite.cancellationPolicy,
            "availableNights" to campsite.availableNights,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (creating) {
            logFailure("Creating campsite ${document.id}") {
                document.set(fields + mapOf(
                    "ownerId" to ownerId,
                    "photoId" to null,
                    "active" to true,
                    "ratingAverage" to 0,
                    "ratingCount" to 0,
                    "createdAt" to FieldValue.serverTimestamp(),
                )).await()
            }
            if (coverJpegBase64 != null) {
                try {
                    commitWithCover(document, mapOf("updatedAt" to FieldValue.serverTimestamp()), ownerId, coverJpegBase64)
                } catch (error: Exception) {
                    throw CoverPhotoNotSavedException(document.id, error)
                }
            }
        } else {
            commitWithCover(document, fields, ownerId, coverJpegBase64)
        }
        return document.id
    }

    /**
     * Updates a campsite that already exists, adding a new cover photo in the same batch when given.
     * @param document The campsite.
     * @param fields Campsite fields to change.
     * @param ownerId The signed-in owner.
     * @param coverJpegBase64 A new Base64 JPEG cover photo, or null.
     */
    private suspend fun commitWithCover(
        document: DocumentReference, fields: Map<String, Any?>, ownerId: String, coverJpegBase64: String?,
    ) {
        val batch = firestore.batch()
        val changes = fields.toMutableMap()
        if (coverJpegBase64 != null) {
            val photo = firestore.collection("photos").document()
            batch.set(photo, mapOf(
                "ownerId" to ownerId,
                "targetType" to "campsite",
                "targetId" to document.id,
                "imageBase64" to coverJpegBase64,
                "mimeType" to "image/jpeg",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
            changes["photoId"] = photo.id
        }
        batch.update(document, changes)
        logFailure("Updating campsite ${document.id}") { batch.commit().await() }
    }

    /**
     * Runs a write and logs why it failed before rethrowing.
     * The screen shows a short message; Logcat keeps the cause, such as PERMISSION_DENIED from security rules.
     * @param what Describes the write in the log.
     * @param write The write to run.
     */
    private suspend fun logFailure(what: String, write: suspend () -> Unit) {
        try {
            write()
        } catch (error: Exception) {
            Log.w("OwnerCampsites", "$what failed", error)
            throw error
        }
    }

    /**
     * Finds the signed-in user's ID.
     * @throws IllegalStateException If no user is signed in.
     */
    private fun ownerId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid

    /** Shared repository access. */
    companion object {
        /** Repository using the app's default Firebase configuration. */
        val instance: FirebaseOwnerCampsiteRepository by lazy {
            FirebaseOwnerCampsiteRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        }
    }
}

/**
 * Maps a campsite document for its owner. Missing numbers read as zero so an incomplete
 * listing can still be opened and fixed in S13.
 */
private fun DocumentSnapshot.toOwnedCampsite() = Campsite(
    id = id,
    ownerId = getString("ownerId").orEmpty(),
    name = getString("name").orEmpty(),
    locationName = getString("locationName").orEmpty(),
    maxGuests = getLong("maxGuests")?.toInt() ?: 0,
    pricePerNight = getLong("pricePerNight") ?: 0,
    description = getString("description").orEmpty(),
    amenities = (get("amenities") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    tags = (get("tags") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    location = getGeoPoint("location"),
    rules = when (val rules = get("rules")) {
        is String -> rules.lines().filter(String::isNotBlank)
        is List<*> -> rules.filterIsInstance<String>()
        else -> emptyList()
    },
    cancellationPolicy = getString("cancellationPolicy").orEmpty(),
    availableNights = (get("availableNights") as? List<*>)?.filterIsInstance<String>().orEmpty().distinct().sorted(),
    photoId = getString("photoId"),
    ratingAverage = (get("ratingAverage") as? Number)?.toDouble() ?: 0.0,
    ratingCount = getLong("ratingCount") ?: 0,
)

/**
 * Maps a lottery document for lottery badges.
 * @throws IllegalArgumentException If a required field is missing.
 */
private fun DocumentSnapshot.toOwnerLottery() = Lottery(
    id = id,
    ownerId = getString("ownerId").orEmpty(),
    title = getString("title").orEmpty(),
    campsiteIds = (get("campsiteIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    registrationStart = requireNotNull(getTimestamp("registrationStart")),
    registrationEnd = requireNotNull(getTimestamp("registrationEnd")),
    bookingStart = getString("bookingStart").orEmpty(),
    bookingEnd = getString("bookingEnd").orEmpty(),
    numWinners = getLong("numWinners")?.toInt() ?: 0,
    waitlistCapacity = getLong("waitlistCapacity")?.toInt(),
    drawn = getBoolean("drawn") ?: false,
)
