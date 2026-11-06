/* Publishes lottery results to the entrant inbox and delivers optional FCM pushes. */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import java.util.UUID
import java.util.concurrent.ExecutionException

/** A leased push attempt. The token is used only to send this event. */
internal class LotteryPush(
    val eventId: String,
    val userId: String,
    val type: String,
    val token: String,
    val claimId: String,
)

/** Publishes committed outbox events and retries push delivery independently of the draw. */
internal class NotificationDelivery(
    private val firestore: Firestore,
    private val sendPush: (LotteryPush) -> Boolean,
) {
    /** Returns the number of newly completed events and reports failed attempts after the sweep. */
    fun deliverPending(): Int {
        val pending = firestore.collection("lotteryNotificationOutbox")
            .whereEqualTo("deliveryStatus", "pending").get().get().documents
        val failures = mutableListOf<Exception>()
        var completed = 0
        for (event in pending) {
            try {
                val attempt = prepare(event.reference)
                val push = attempt.push
                if (push != null) {
                    val sent = try {
                        sendPush(push)
                    } catch (failure: RuntimeException) {
                        finish(event.reference, push.claimId, "pending")
                        throw failure
                    }
                    finish(event.reference, push.claimId, if (sent) "sent" else "skipped")
                }
                if (attempt.completedWithoutPush || push != null) completed++
            } catch (failure: ExecutionException) {
                failures += IllegalStateException("Delivery failed for event ${event.id}.", failure.cause ?: failure)
            } catch (failure: RuntimeException) {
                failures += IllegalStateException("Delivery failed for event ${event.id}.", failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Completed $completed notification(s), ${failures.size} failed.")
                .also { summary -> failures.forEach(summary::addSuppressed) }
        }
        return completed
    }

    /** Indicates whether to send a push, count a skipped push, or leave a claimed event alone. */
    private data class Prepared(val push: LotteryPush? = null, val completedWithoutPush: Boolean = false)

    /** Publishes an inbox item once and acquires a temporary push claim when delivery is enabled. */
    private fun prepare(reference: DocumentReference): Prepared = firestore.runTransaction { transaction ->
        val event = transaction.get(reference).get()
        if (event.getString("deliveryStatus") != "pending") return@runTransaction Prepared()
        val now = Timestamp.now()
        val lease = event.getTimestamp("pushClaimUntil")
        if (lease != null && lease > now) return@runTransaction Prepared()

        val userId = checkNotNull(event.getString("toUserId"))
        val type = checkNotNull(event.getString("type"))
        val offerId = event.getString("offerId")
        check((type == "lottery_won" && !offerId.isNullOrBlank()) || (type == "lottery_lost" && offerId == null)) {
            "Invalid lottery notification result."
        }
        val profile = transaction.get(firestore.collection("users").document(userId)).get()
        val active = profile.exists() && profile.getBoolean("disabled") != true
        if (active) {
            val inbox = firestore.collection("notifications").document(event.id)
            if (!transaction.get(inbox).get().exists()) {
                transaction.create(inbox, mapOf(
                    "toUserId" to userId,
                    "type" to type,
                    "lotteryId" to checkNotNull(event.getString("lotteryId")),
                    "offerId" to offerId,
                    "createdAt" to checkNotNull(event.getTimestamp("createdAt")),
                ))
            }
        }

        val token = if (active && profile.getBoolean("notificationsEnabled") == true) profile.getString("fcmToken") else null
        if (token.isNullOrBlank()) {
            transaction.update(reference, deliveryFields("skipped"))
            return@runTransaction Prepared(completedWithoutPush = true)
        }

        val claimId = UUID.randomUUID().toString()
        transaction.update(reference, mapOf(
            "pushClaimId" to claimId,
            "pushClaimUntil" to Timestamp.ofTimeSecondsAndNanos(now.seconds + 120, now.nanos),
            "updatedAt" to FieldValue.serverTimestamp(),
        ))
        Prepared(LotteryPush(event.id, userId, type, token, claimId))
    }.get()

    /** Records the outcome only if this attempt still owns the push claim. */
    private fun finish(reference: DocumentReference, claimId: String, status: String) {
        firestore.runTransaction { transaction ->
            if (transaction.get(reference).get().getString("pushClaimId") == claimId) {
                transaction.update(reference, deliveryFields(status))
            }
        }.get()
    }

    /** Clears the claim and sets the outcome or returns the event to pending. */
    private fun deliveryFields(status: String): Map<String, Any> = mapOf(
        "deliveryStatus" to status,
        "pushClaimId" to FieldValue.delete(),
        "pushClaimUntil" to FieldValue.delete(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}
