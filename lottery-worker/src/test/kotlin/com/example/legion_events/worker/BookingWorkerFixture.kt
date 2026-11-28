/* BookingWorkerFixture.kt: synthetic booking data shared by local-emulator integration tests. */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.util.UUID

/** Opens an isolated demo namespace for every test; never connects to a live project. */
abstract class BookingWorkerFixture {
    protected lateinit var firestore: Firestore
    protected lateinit var projectId: String
    protected val accepted: Timestamp = Timestamp.now().let { Timestamp.ofTimeSecondsAndNanos(it.seconds - 60, 0) }
    protected val start: Timestamp = Timestamp.ofTimeSecondsAndNanos((accepted.seconds / 86400 + 1) * 86400, 0)
    protected val end: Timestamp = Timestamp.ofTimeSecondsAndNanos(start.seconds + 2 * 86400, 0)
    protected val expiry: Timestamp = Timestamp.ofTimeSecondsAndNanos(accepted.seconds + 86400, 0)

    @Before fun connectBookingEmulator() {
        val host = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
        assumeTrue("Set FIRESTORE_EMULATOR_HOST for booking integration tests.", host.isNotBlank())
        require(host.matches(Regex("(127\\.0\\.0\\.1|localhost):[0-9]+"))) { "Use a loopback emulator." }
        projectId = "demo-booking-${UUID.randomUUID().toString().take(12)}"
        firestore = FirestoreOptions.newBuilder().setProjectId(projectId).setEmulatorHost(host).build().service
    }
    @After fun closeBookingEmulator() { if (::firestore.isInitialized) firestore.close() }

    /** Creates a valid, explicitly accepted request and its authoritative dependencies. */
    protected fun seedRequest(id: String = "booking", uid: String = "alice", fields: Map<String, Any?> = emptyMap()) {
        seedLottery()
        put("users/$uid", mapOf("disabled" to false, "notificationsEnabled" to false))
        put("campsites/pine", mapOf("ownerId" to "owner", "active" to true, "maxGuests" to 4,
            "pricePerNight" to 1500, "availableNights" to bookingNights(start, end), "rules" to "Quiet hours",
            "cancellationPolicy" to "No refunds"))
        put("reservations/$id", requestFields(uid) + fields)
        put("offers/draw_$uid", mapOf("lotteryId" to "draw", "userId" to uid, "campsiteIds" to listOf("pine"),
            "status" to "pending", "response" to "accept", "reservationId" to id,
            "respondedAt" to accepted, "expiresAt" to expiry, "createdAt" to accepted, "updatedAt" to accepted))
    }
    protected fun seedLottery() = put("lotteries/draw", mapOf("ownerId" to "owner", "campsiteIds" to listOf("pine"),
        "winnersDrawn" to true, "winnerCount" to 2, "entrantCount" to 5,
        "bookingStartAt" to Timestamp.ofTimeSecondsAndNanos(accepted.seconds - 3600, 0), "bookingEndAt" to expiry,
        "status" to "open", "updatedAt" to accepted))
    protected fun requestFields(uid: String = "alice"): Map<String, Any?> = mapOf("lotteryId" to "draw",
        "offerId" to "draw_$uid", "campsiteId" to "pine", "userId" to uid, "ownerId" to "owner",
        "startDate" to start, "endDate" to end, "guestCount" to 2, "totalPrice" to 3000,
        "rulesAccepted" to true, "status" to "requested", "cancellationRequested" to false,
        "createdAt" to accepted, "updatedAt" to accepted)
    protected fun put(path: String, fields: Map<String, Any?>) { firestore.document(path).set(fields).get() }
    protected fun update(path: String, fields: Map<String, Any?>) { firestore.document(path).update(fields).get() }
    protected fun data(path: String): Map<String, Any> = checkNotNull(firestore.document(path).get().get().data)
    protected fun exists(path: String): Boolean = firestore.document(path).get().get().exists()
    protected fun locks() = firestore.collection("bookedNights").get().get().documents
}
