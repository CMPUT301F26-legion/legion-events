/*
 * LotteryWorkerTest.kt
 *
 * Purpose: Verifies draw results, retries, and rollback against the local Firestore emulator.
 * Role: Integration tests for the standalone worker using synthetic lotteries and entrants.
 */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the draw transaction with an isolated demo project and fixed deadlines. */
class LotteryWorkerTest {
    private lateinit var firestore: Firestore
    private lateinit var worker: LotteryWorker
    private val deadline = Timestamp.parseTimestamp("2026-10-31T12:00:00Z")
    private val expiresAt = Timestamp.parseTimestamp("2026-11-07T12:00:00Z")

    /** Opens a fresh demo project for each test when a local emulator is configured. */
    @Before
    fun connectToEmulator() {
        val host = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
        assumeTrue("Set FIRESTORE_EMULATOR_HOST to run the integration tests.", host.isNotBlank())
        val projectId = "demo-legion-${UUID.randomUUID().toString().take(12)}"
        firestore = FirestoreOptions.newBuilder().setProjectId(projectId).setEmulatorHost(host).build().service
        worker = LotteryWorker(firestore)
    }

    /** Releases the Firestore client if test setup opened a connection. */
    @After
    fun closeFirestore() {
        if (::firestore.isInitialized) firestore.close()
    }

    /** Checks deadline eligibility, unique results, offer and event contents, and a repeated run. */
    @Test
    fun `draw at deadline creates one result per user and offers for unique winners`() {
        seedLottery("due", winnerCount = 2, entrantCount = 3)
        seedLottery("later", endAt = Timestamp.ofTimeSecondsAndNanos(deadline.seconds, 1_000))
        seedLottery("cancelled", status = "cancelled")
        seedLottery("already-drawn", winnersDrawn = true)
        seedEntry("due", "alice")
        seedEntry("due", "alice", id = "duplicate")
        seedEntry("due", "bob")
        seedEntry("due", "carol")
        seedEntry("due", "dan", status = "withdrawn")
        seedEntry("later", "erin")

        assertEquals(1, worker.drawDueLotteries(deadline))
        val drawn = firestore.document("lotteries/due").get().get()
        assertEquals(true, drawn.getBoolean("winnersDrawn"))
        assertEquals(3L, drawn.getLong("entrantCount"))
        assertEquals(false, firestore.document("lotteries/later").get().get().getBoolean("winnersDrawn"))
        assertEquals(false, firestore.document("lotteries/cancelled").get().get().getBoolean("winnersDrawn"))
        assertEquals("joined", statusOf("waitlist/later_erin"))
        assertEquals("withdrawn", statusOf("waitlist/due_dan"))
        assertEquals(statusOf("waitlist/due_alice"), statusOf("waitlist/duplicate"))

        val offers = firestore.collection("offers").get().get().documents
        assertEquals(2, offers.size)
        assertEquals(2, offers.map { it.getString("userId") }.toSet().size)
        val notifications = firestore.collection("lotteryNotificationOutbox").get().get().documents
        assertEquals(3, notifications.size)
        assertEquals(setOf("alice", "bob", "carol"), notifications.map { it.getString("toUserId") }.toSet())
        for (notification in notifications) {
            val userId = notification.getString("toUserId")
            val offer = offers.singleOrNull { it.getString("userId") == userId }
            assertEquals("pending", notification.getString("deliveryStatus"))
            assertEquals(offer?.id, notification.getString("offerId"))
            if (offer == null) {
                assertEquals("lottery_lost", notification.getString("type"))
                assertEquals("lost", statusOf("waitlist/due_$userId"))
            } else {
                assertEquals("lottery_won", notification.getString("type"))
                assertEquals("won", statusOf("waitlist/due_$userId"))
                assertEquals("due", offer.getString("lotteryId"))
                assertEquals("pending", offer.getString("status"))
                assertEquals(null, offer.get("response"))
                assertEquals(listOf("camp-a", "camp-b"), offer.get("campsiteIds"))
                assertEquals(expiresAt, offer.getTimestamp("expiresAt"))
                assertEquals(drawn.getTimestamp("updatedAt"), offer.getTimestamp("createdAt"))
            }
        }

        assertEquals(0, worker.drawDueLotteries(deadline))
        assertEquals(drawn.data, firestore.document("lotteries/due").get().get().data)
        assertEquals(offers.map { it.data }, firestore.collection("offers").get().get().documents.map { it.data })
    }

    /** Checks that an empty waitlist completes and a short waitlist caps the number of winners. */
    @Test
    fun `empty lottery is marked drawn and a short waitlist receives only available winners`() {
        seedLottery("empty")
        seedLottery("short", winnerCount = 3)
        seedEntry("short", "alice")

        assertEquals(2, worker.drawDueLotteries(deadline))
        assertEquals(true, firestore.document("lotteries/empty").get().get().getBoolean("winnersDrawn"))
        assertEquals(listOf("short_alice"), firestore.collection("offers").get().get().documents.map { it.id })
        assertEquals(1, firestore.collection("lotteryNotificationOutbox").get().get().size())
    }

    /** Races two draws for one lottery and checks that a single offer and event are committed. */
    @Test
    fun `two simultaneous attempts commit only one draw`() {
        seedLottery("due")
        seedEntry("due", "alice")
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val attempts = List(2) {
                pool.submit<Int> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    worker.drawDueLotteries(deadline)
                }
            }
            start.countDown()
            assertEquals(listOf(0, 1), attempts.map { it.get(45, TimeUnit.SECONDS) }.sorted())
        }
        assertEquals(1, firestore.collection("offers").get().get().size())
        assertEquals(1, firestore.collection("lotteryNotificationOutbox").get().get().size())
    }

    /** Checks that an existing offer rolls back its draw while another due lottery can complete. */
    @Test
    fun `offer conflict rolls back the lottery and the sweep still draws other lotteries`() {
        seedLottery("conflict")
        seedEntry("conflict", "alice")
        seedLottery("ready")
        seedEntry("ready", "bob")
        val priorOffer = mapOf("status" to "pending", "response" to "decline")
        firestore.document("offers/conflict_alice").create(priorOffer).get()

        val failure = assertThrows(IllegalStateException::class.java) { worker.drawDueLotteries(deadline) }
        assertTrue(failure.suppressed.single().message.orEmpty().contains("conflict"))
        assertEquals(false, firestore.document("lotteries/conflict").get().get().getBoolean("winnersDrawn"))
        assertEquals("joined", statusOf("waitlist/conflict_alice"))
        assertFalse(firestore.document("lotteryNotificationOutbox/conflict_alice").get().get().exists())
        assertEquals(priorOffer, firestore.document("offers/conflict_alice").get().get().data)
        assertEquals(true, firestore.document("lotteries/ready").get().get().getBoolean("winnersDrawn"))
        assertEquals("won", statusOf("waitlist/ready_bob"))
    }

    /** Publishes actual draw events and sends pushes only to recipients who opted in. */
    @Test
    fun `draw results reach both inboxes and only enabled recipients receive pushes`() {
        seedLottery("due", entrantCount = 2)
        seedEntry("due", "alice")
        seedEntry("due", "bob")
        firestore.document("users/alice").create(mapOf("notificationsEnabled" to true, "fcmToken" to "alice-token")).get()
        firestore.document("users/bob").create(mapOf("notificationsEnabled" to false, "fcmToken" to "bob-token")).get()
        assertEquals(1, worker.drawDueLotteries(deadline))

        val pushes = mutableListOf<LotteryPush>()
        val delivery = NotificationDelivery(firestore) { pushes += it; true }
        assertEquals(2, delivery.deliverPending())
        assertEquals("alice-token", pushes.single().token)
        assertEquals("due_alice", pushes.single().eventId)
        val inbox = firestore.collection("notifications").get().get().documents
        assertEquals(setOf("lottery_won", "lottery_lost"), inbox.map { it.getString("type") }.toSet())
        for (item in inbox) {
            val event = firestore.document("lotteryNotificationOutbox/${item.id}").get().get()
            assertEquals(event.getString("toUserId"), item.getString("toUserId"))
            assertEquals(event.getString("offerId"), item.getString("offerId"))
            assertEquals(event.getTimestamp("createdAt"), item.getTimestamp("createdAt"))
            assertFalse(item.contains("fcmToken"))
            assertEquals(if (item.id == "due_alice") "sent" else "skipped", event.getString("deliveryStatus"))
        }
        assertEquals(0, delivery.deliverPending())
        assertEquals(1, pushes.size)
    }

    /** A failed push can retry without overwriting the inbox record. */
    @Test
    fun `push failure keeps one inbox item and is retried independently`() {
        seedLottery("due")
        seedEntry("due", "alice")
        firestore.document("users/alice").create(mapOf("notificationsEnabled" to true, "fcmToken" to "test-token")).get()
        worker.drawDueLotteries(deadline)

        val failing = NotificationDelivery(firestore) { error("Simulated push failure") }
        assertThrows(IllegalStateException::class.java) { failing.deliverPending() }
        val inbox = firestore.document("notifications/due_alice")
        inbox.update("read", true).get()
        val beforeRetry = inbox.get().get().data
        val outbox = firestore.document("lotteryNotificationOutbox/due_alice")
        assertEquals("pending", outbox.get().get().getString("deliveryStatus"))

        assertEquals(1, NotificationDelivery(firestore) { true }.deliverPending())
        assertEquals("sent", outbox.get().get().getString("deliveryStatus"))
        assertEquals(beforeRetry, inbox.get().get().data)
        assertEquals(1, firestore.collection("notifications").get().get().size())
    }

    /** While a worker owns a push claim, a concurrent sweep leaves it alone. */
    @Test
    fun `concurrent delivery does not send the same push twice`() {
        seedLottery("due")
        seedEntry("due", "alice")
        firestore.document("users/alice").create(mapOf("notificationsEnabled" to true, "fcmToken" to "test-token")).get()
        worker.drawDueLotteries(deadline)

        val claimed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { pool ->
            val first = pool.submit<Int> {
                NotificationDelivery(firestore) {
                    claimed.countDown()
                    assertTrue(finish.await(20, TimeUnit.SECONDS))
                    true
                }.deliverPending()
            }
            try {
                assertTrue(claimed.await(15, TimeUnit.SECONDS))
                assertEquals(0, NotificationDelivery(firestore) { error("Duplicate send") }.deliverPending())
            } finally {
                finish.countDown()
            }
            assertEquals(1, first.get(20, TimeUnit.SECONDS))
        }
    }

    /**
     * Creates a synthetic lottery for a draw test.
     *
     * @param id Lottery document ID.
     * @param endAt Registration deadline for the fixture.
     * @param winnerCount Requested number of winners.
     * @param entrantCount Stored count that the draw is expected to preserve.
     * @param status Initial lottery status.
     * @param winnersDrawn Initial draw flag for fixtures that simulate a completed lottery.
     */
    private fun seedLottery(
        id: String,
        endAt: Timestamp = deadline,
        winnerCount: Int = 1,
        entrantCount: Int = 0,
        status: String = "open",
        winnersDrawn: Boolean = false,
    ) {
        firestore.document("lotteries/$id").create(
            mapOf(
                "registrationEndAt" to endAt,
                "bookingEndAt" to expiresAt,
                "winnerCount" to winnerCount,
                "entrantCount" to entrantCount,
                "campsiteIds" to listOf("camp-a", "camp-b"),
                "status" to status,
                "winnersDrawn" to winnersDrawn,
            ),
        ).get()
    }

    /**
     * Creates a synthetic waitlist entry for the specified lottery and user.
     *
     * @param lotteryId Lottery that the user entered.
     * @param userId Entrant's test UID.
     * @param status Initial waitlist status.
     * @param id Entry document ID. An alternate ID can simulate a duplicate entry for the same user.
     */
    private fun seedEntry(lotteryId: String, userId: String, status: String = "joined", id: String = "${lotteryId}_$userId") {
        firestore.document("waitlist/$id").create(mapOf("lotteryId" to lotteryId, "userId" to userId, "status" to status)).get()
    }

    /**
     * Reads the saved status for an assertion.
     *
     * @param path Firestore document path to read.
     * @return The status string, or null when the field is absent.
     */
    private fun statusOf(path: String) = firestore.document(path).get().get().getString("status")
}
