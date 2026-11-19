/*
 * InboxNotification.kt
 *
 * Purpose: Represent saved lottery and completed cancellation notices in S07.
 * Role: Model in MVC; supplies stable identity and time for the shared inbox.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/** Common ordering fields for trusted worker notifications. */
sealed interface InboxNotification {
    val id: String
    val createdAt: Timestamp
}

/**
 * A notice committed atomically with a completed cancellation.
 * @property id Stable notification document ID.
 * @property reservationId Saved reservation to reopen for confirmation and CSV.
 * @property createdAt Server time when the cancellation and released dates committed.
 */
data class CancellationNotification(
    override val id: String,
    val reservationId: String,
    override val createdAt: Timestamp,
) : InboxNotification
