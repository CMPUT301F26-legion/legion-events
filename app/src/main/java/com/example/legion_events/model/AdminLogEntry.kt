/*
 * AdminLogEntry.kt
 *
 * Purpose: One row of the S20 admin logs: a notification the app sent, or a message an owner sent
 *   to entrants (US 03.08.01, 03.12.01).
 * Role: Model in MVC; AdminLogRepository builds entries and AdminLogsController labels them.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/** Which S20 tab an entry belongs to. */
enum class AdminLogCategory {
    /** A notification the app sent to a user: lottery results and cancellations (US 03.08.01). */
    NOTIFICATION,
    /** A message from a campsite owner to entrants: notices and direct messages (US 03.12.01). */
    MESSAGE,
}

/**
 * A logged notification or owner-to-entrant message.
 * @property id The source document ID.
 * @property category Which tab lists it.
 * @property type The stored type, such as `lottery_won`, or [TYPE_OWNER_NOTICE] / [TYPE_DIRECT_MESSAGE].
 * @property fromUserId Who sent it, or null when the app sent it.
 * @property toUserIds Who received it.
 * @property text The message body, or an empty string when the notification has none.
 * @property lotteryId The lottery it concerns, or null.
 * @property createdAt When it was sent, or null if unknown.
 */
data class AdminLogEntry(
    val id: String,
    val category: AdminLogCategory,
    val type: String,
    val fromUserId: String?,
    val toUserIds: List<String>,
    val text: String,
    val lotteryId: String?,
    val createdAt: Timestamp?,
) {
    companion object {
        /** An `ownerNotices` broadcast from an owner to a lottery's entrants. */
        const val TYPE_OWNER_NOTICE = "owner_notice"
        /** A `messages` document sent by the conversation's owner to its entrant. */
        const val TYPE_DIRECT_MESSAGE = "direct_message"
    }
}
