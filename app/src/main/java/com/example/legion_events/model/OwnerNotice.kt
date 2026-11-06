/*
 * OwnerNotice.kt
 *
 * Purpose: A one-way notice an owner sends to a lottery's waitlist (US 02.10.01, 02.10.02).
 * Role: Model in MVC; written on S18 and shown in the owner's sent list and the entrants' inbox.
 * Outstanding issues: No push notification is sent; entrants see notices in the S07 inbox.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/** Longest notice the app sends; the Firestore rules accept the same length. */
const val MAX_NOTICE_LENGTH = 1000

/**
 * One notice stored at `ownerNotices/{id}`.
 * @property id Firestore document ID.
 * @property ownerId The owner who sent it.
 * @property lotteryId The lottery whose waitlist received it.
 * @property message The text the owner wrote, 1 to [MAX_NOTICE_LENGTH] characters.
 * @property recipientIds Entrants who were on the waitlist when it was sent.
 * @property createdAt When it was sent, or null until the server sets it.
 */
data class OwnerNotice(
    val id: String,
    val ownerId: String,
    val lotteryId: String,
    val message: String,
    val recipientIds: List<String>,
    val createdAt: Timestamp?,
)
