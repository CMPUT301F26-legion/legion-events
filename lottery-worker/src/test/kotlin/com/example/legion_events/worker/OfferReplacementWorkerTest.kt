/* OfferReplacementWorkerTest.kt: preserves the initial draw while replacing a declined winner once. */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OfferReplacementWorkerTest : BookingWorkerFixture() {
    private fun seedDecline() {
        seedLottery()
        val entrants = listOf("alice", "bob", "carol", "dan", "erin")
        for (uid in entrants) {
            put("users/$uid", mapOf("disabled" to (uid in listOf("dan", "erin")), "notificationsEnabled" to false))
            put("waitlist/draw_$uid", mapOf("lotteryId" to "draw", "userId" to uid,
                "status" to if (uid in listOf("alice", "bob")) "won" else "lost", "updatedAt" to accepted))
        }
        for (uid in listOf("alice", "bob")) put("offers/draw_$uid", mapOf("lotteryId" to "draw", "userId" to uid,
            "campsiteIds" to listOf("pine"), "status" to if (uid == "bob") "used" else "pending",
            "response" to if (uid == "alice") "decline" else "accept", "expiresAt" to expiry,
            "createdAt" to accepted, "updatedAt" to accepted))
        put("reservations/bob-booking", requestFields("bob") + mapOf("status" to "confirmed"))
        put("lotteryDraws/draw", mapOf("lotteryId" to "draw", "ownerId" to "owner",
            "entrantIds" to entrants, "winnerIds" to listOf("alice", "bob"), "createdAt" to accepted))
        val loss = mapOf("lotteryId" to "draw", "toUserId" to "carol", "offerId" to null,
            "type" to "lottery_lost", "createdAt" to accepted)
        put("notifications/draw_carol", loss)
        put("lotteryNotificationOutbox/draw_carol", loss + mapOf("deliveryStatus" to "pending", "updatedAt" to accepted))
    }
    @Test fun declineSelectsOneRemainingEntrantAndKeepsOtherWinnersBookingsAndDeadline() {
        seedDecline()
        val lottery = data("lotteries/draw")
        val manifest = data("lotteryDraws/draw")
        val bob = data("offers/draw_bob")
        val booking = data("reservations/bob-booking")
        val loss = data("notifications/draw_carol")
        val worker = OfferReplacementWorker(firestore)
        assertEquals(1, worker.processPending())
        val replacement = data("offers/draw_carol")
        assertEquals("pending", replacement["status"])
        assertNull(replacement["response"])
        assertEquals(expiry, replacement["expiresAt"])
        assertEquals("won", data("waitlist/draw_carol")["status"])
        assertEquals(lottery, data("lotteries/draw"))
        assertEquals(manifest, data("lotteryDraws/draw"))
        assertEquals(bob, data("offers/draw_bob"))
        assertEquals(booking, data("reservations/bob-booking"))
        assertEquals(loss, data("notifications/draw_carol"))
        val notice = data("notifications/lottery_replacement_draw_alice")
        assertEquals("lottery_won", notice["type"])
        assertEquals("draw_carol", notice["offerId"])
        assertEquals("carol", notice["toUserId"])
        assertEquals(replacement["createdAt"], notice["createdAt"])
        val marker = data("offerReplacements/draw_alice")
        assertEquals("carol", marker["replacementUserId"])
        assertEquals(0, worker.processPending())
        assertEquals(marker, data("offerReplacements/draw_alice"))
        assertEquals(notice, data("notifications/lottery_replacement_draw_alice"))
        // Existing optional delivery accepts the replacement event's normal won payload.
        assertEquals(2, NotificationDelivery(firestore) { error("Push disabled") }.deliverPending())
    }
    @Test fun anotherDeclineReplacesOnlyThatWinnerAndNeverReinvitesAnEarlierWinner() {
        seedDecline()
        val worker = OfferReplacementWorker(firestore)
        assertTrue(worker.processOffer("draw_alice"))
        update("users/dan", mapOf("disabled" to false))
        update("offers/draw_carol", mapOf("response" to "decline"))
        assertTrue(worker.processOffer("draw_carol"))
        assertEquals("dan", data("offerReplacements/draw_carol")["replacementUserId"])
        assertEquals("used", data("offers/draw_bob")["status"])
        assertEquals(4, firestore.collection("offers").get().get().size())
    }
    @Test fun noEligibleEntrantsOrExpiredDeadlineRecordsANullDecisionWithoutExtendingAnything() {
        seedDecline()
        update("users/carol", mapOf("disabled" to true))
        val worker = OfferReplacementWorker(firestore)
        assertFalse(worker.processOffer("draw_alice"))
        assertNull(data("offerReplacements/draw_alice")["replacementUserId"])
        update("users/carol", mapOf("disabled" to false))
        assertFalse(worker.processOffer("draw_alice"))
        assertFalse(exists("offers/draw_carol"))
        firestore.document("offerReplacements/draw_alice").delete().get()
        assertFalse(OfferReplacementWorker(firestore) { expiry }.processOffer("draw_alice"))
        assertNull(data("offerReplacements/draw_alice")["replacementUserId"])
        assertEquals(expiry, data("offers/draw_alice")["expiresAt"])
    }
    @Test fun withdrawnMissingDisabledPreviouslySelectedAndAlreadyBookedEntrantsAreExcluded() {
        seedDecline()
        firestore.document("users/carol").delete().get()
        assertFalse(OfferReplacementWorker(firestore).processOffer("draw_alice"))
        firestore.document("offerReplacements/draw_alice").delete().get()
        put("users/carol", mapOf("disabled" to false))
        update("waitlist/draw_carol", mapOf("status" to "withdrawn"))
        assertFalse(OfferReplacementWorker(firestore).processOffer("draw_alice"))
        firestore.document("offerReplacements/draw_alice").delete().get()
        update("waitlist/draw_carol", mapOf("status" to "lost"))
        put("offers/draw_carol", mapOf("lotteryId" to "draw", "userId" to "carol", "status" to "expired"))
        assertFalse(OfferReplacementWorker(firestore).processOffer("draw_alice"))
        firestore.document("offerReplacements/draw_alice").delete().get()
        firestore.document("offers/draw_carol").delete().get()
        put("reservations/carol-booking", requestFields("carol") + mapOf("status" to "confirmed"))
        assertFalse(OfferReplacementWorker(firestore).processOffer("draw_alice"))
    }
    @Test fun aConflictingDestinationRollsBackTheReplacementAndItsMarker() {
        seedDecline()
        put("notifications/lottery_replacement_draw_alice", mapOf("existing" to true))
        assertThrows(IllegalStateException::class.java) { OfferReplacementWorker(firestore).processPending() }
        assertFalse(exists("offerReplacements/draw_alice"))
        assertFalse(exists("offers/draw_carol"))
        assertEquals("lost", data("waitlist/draw_carol")["status"])
        assertFalse(exists("lotteryNotificationOutbox/lottery_replacement_draw_alice"))
    }
    @Test fun simultaneousWorkersAndDifferentDeclinesCannotSelectTheSameReplacementTwice() {
        seedDecline()
        update("users/dan", mapOf("disabled" to false))
        update("offers/draw_bob", mapOf("status" to "pending", "response" to "decline"))
        firestore.document("reservations/bob-booking").delete().get()
        val ready = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<Int> { ready.await(); OfferReplacementWorker(firestore).processPending() } }
            ready.countDown()
            assertEquals(2, calls.sumOf { it.get(45, TimeUnit.SECONDS) })
        }
        val winners = firestore.collection("offerReplacements").get().get().documents.map { it.getString("replacementUserId") }
        assertEquals(setOf("carol", "dan"), winners.toSet())
        assertEquals(2, winners.size)
        assertEquals(4, firestore.collection("offers").get().get().size())
    }
    @Test fun openAcceptedAndUsedOffersCannotTriggerAReplacement() {
        seedDecline()
        for (fields in listOf(mapOf("response" to null), mapOf("response" to "accept"), mapOf("status" to "used", "response" to "decline"))) {
            update("offers/draw_alice", mapOf("status" to "pending", "response" to "decline") + fields)
            assertFalse(OfferReplacementWorker(firestore).processOffer("draw_alice"))
        }
        assertFalse(exists("offerReplacements/draw_alice"))
    }
    @Test fun cloudDrawsWithoutAnAndroidManifestCanStillReplaceOneLostEntrant() {
        seedDecline()
        firestore.document("lotteryDraws/draw").delete().get()
        assertTrue(OfferReplacementWorker(firestore).processOffer("draw_alice"))
        assertFalse(exists("lotteryDraws/draw"))
    }
}
