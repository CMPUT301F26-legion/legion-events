package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * One `waitlist/{lotteryId}_{userId}` entry as the lottery owner sees it on S16 (US 02.09.01).
 * Read-only; joining and leaving belong to the waitlist feature, which writes these documents.
 * @property userId The entrant's user ID. Owners cannot read the entrant's profile under the current rules.
 * @property status `joined`, `withdrawn`, or a draw result such as `selected`.
 * @property joinedAt When the entrant last joined, or null if missing.
 */
data class WaitlistEntrant(
    val userId: String,
    val status: String,
    val joinedAt: Timestamp?,
)
