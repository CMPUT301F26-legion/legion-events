/*
 * WaitlistEntrant.kt
 *
 * Purpose: One entrant's entry on a lottery waitlist (US 01.02.01, 01.02.02, 02.09.01).
 * Role: Model in MVC; read by S16 for owners and by S04/S06 for the entrant's own entries.
 * Outstanding issues: None.
 */
package com.example.legion_events.model

import com.google.firebase.Timestamp

/**
 * One `waitlist/{lotteryId}_{userId}` entry. Owners read it on S16 (US 02.09.01); entrants
 * create it by joining (US 01.02.01) and mark it [STATUS_WITHDRAWN] by leaving (US 01.02.02).
 * @property userId The entrant's user ID. Owners cannot read the entrant's profile under the current rules.
 * @property status [STATUS_JOINED], [STATUS_WITHDRAWN], or a draw result such as `won` or `lost`.
 * @property joinedAt When the entrant last joined, or null if missing.
 * @property lotteryId The lottery this entry belongs to; empty when the reader already knows it.
 */
data class WaitlistEntrant(
    val userId: String,
    val status: String,
    val joinedAt: Timestamp?,
    val lotteryId: String = "",
) {
    companion object {
        /** Stored status while the entrant is on the waitlist. */
        const val STATUS_JOINED = "joined"
        /** Stored status after the entrant leaves; the security rules allow joining again. */
        const val STATUS_WITHDRAWN = "withdrawn"
    }
}
