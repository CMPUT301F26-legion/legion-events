/* Models the lottery result saved in an entrant's inbox. */
package com.example.legion_events.model

import androidx.annotation.StringRes
import com.example.legion_events.R
import com.google.firebase.Timestamp

/** The stored result type and its matching inbox and push copy. */
enum class LotteryResult(val storedType: String, @StringRes val title: Int, @StringRes val message: Int) {
    WON("lottery_won", R.string.lottery_won_title, R.string.lottery_won_message),
    LOST("lottery_lost", R.string.lottery_lost_title, R.string.lottery_lost_message);

    companion object {
        /** Resolves a known lottery result, or null for an unrelated notification type. */
        fun fromStoredType(type: String?): LotteryResult? = entries.firstOrNull { it.storedType == type }
    }
}

/**
 * A committed win or loss shown to the recipient of the draw.
 * @property id Stable event ID used by the backend and inbox.
 * @property result Win or loss displayed in the inbox.
 * @property offerId Booking offer document ID for a win, or null for a loss.
 * @property createdAt Firestore timestamp for the draw result.
 */
data class LotteryNotification(
    val id: String,
    val result: LotteryResult,
    val offerId: String?,
    val createdAt: Timestamp,
)
