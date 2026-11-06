/*
 * NotificationOutbox.kt
 *
 * Purpose: Queues win and loss events when the lottery result commits (US 01.03.01 and 01.03.02).
 * Role: Firestore handoff from the draw worker to Part 5's notification service.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction

/**
 * Stages one pending notification event under `lotteryNotificationOutbox/{lotteryId}_{toUserId}`.
 * The event commits with the draw and is available to the inbox and push service after commit.
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
    transaction.create(
        firestore.collection("lotteryNotificationOutbox").document("${lotteryId}_$toUserId"),
        mapOf(
            "type" to if (offerId == null) "lottery_lost" else "lottery_won",
            "toUserId" to toUserId,
            "lotteryId" to lotteryId,
            "offerId" to offerId,
            "deliveryStatus" to "pending",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        ),
    )
}
