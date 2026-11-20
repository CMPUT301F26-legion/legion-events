/*
 * NotificationOutbox.kt
 *
 * Purpose: Saves Inbox results and queues optional push delivery in the draw transaction.
 * Role: Firestore handoff from the draw worker to Part 5's notification service.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction

/**
 * Saves one Inbox result and one pending push event using the same stable document ID.
 * Both commit with the draw, so the winner's offer and Inbox result appear together even with push disabled.
 *
 * @param transaction Active draw transaction. Call after its reads are complete.
 * @param firestore Firestore client for the database used by the transaction.
 * @param lotteryId Document ID of the lottery being drawn.
 * @param toUserId Firebase Auth UID of the entrant receiving the result.
 * @param offerId Booking offer document ID for a win, or null for a loss.
 */
internal fun sendNotification(
    transaction: Transaction,
    firestore: Firestore,
    lotteryId: String,
    toUserId: String,
    offerId: String?,
) {
    val eventId = "${lotteryId}_$toUserId"
    val result = mapOf(
        "type" to if (offerId == null) "lottery_lost" else "lottery_won",
        "toUserId" to toUserId,
        "lotteryId" to lotteryId,
        "offerId" to offerId,
        "createdAt" to FieldValue.serverTimestamp(),
    )
    transaction.create(firestore.collection("notifications").document(eventId), result)
    transaction.create(
        firestore.collection("lotteryNotificationOutbox").document(eventId),
        result + mapOf(
            "deliveryStatus" to "pending",
            "updatedAt" to FieldValue.serverTimestamp(),
        ),
    )
}
