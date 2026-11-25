/* Tests saved deadline edits and timer recovery through the backend HTTP entry points. */
package com.example.legion_events.worker

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Verifies deadline triggers and durable results using a local Firestore emulator and HTTP server. */
class LotteryAutomationServerTest {
    private lateinit var firestore: Firestore
    private lateinit var server: LotteryAutomationServer
    private lateinit var projectId: String
    private val client = HttpClient.newHttpClient()

    /** Opens a separate demo database and listens on a temporary loopback port. */
    @Before fun setUp() {
        val host = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
        assumeTrue("Set FIRESTORE_EMULATOR_HOST for the deadline integration tests.", host.isNotBlank())
        projectId = "demo-deadline-${UUID.randomUUID().toString().take(10)}"
        firestore = FirestoreOptions.newBuilder().setProjectId(projectId).setEmulatorHost(host).build().service
        server = LotteryAutomationServer(firestore, projectId, InetSocketAddress("127.0.0.1", 0))
        server.start()
    }

    /** Releases the listener and database client after each test. */
    @After fun tearDown() {
        if (::server.isInitialized) server.close()
        if (::firestore.isInitialized) firestore.close()
        client.close()
    }

    /** Reproduces the demo with two lotteries, then verifies offer and Inbox publication without push delivery. */
    @Test fun `moving two deadlines into the past publishes each winning offer and inbox exactly once`() {
        for (id in listOf("pine", "oak")) {
            seedLottery(id, 3600)
            assertEquals(200, request("/events", id).statusCode())
            assertEquals(false, firestore.document("lotteries/$id").get().get().getBoolean("winnersDrawn"))
            firestore.document("lotteries/$id").update("registrationEndAt", time(-1)).get()
            assertEquals(200, request("/events", id).statusCode())
            assertWinningResult(id)
        }
        val before = firestore.document("notifications/pine_alice").get().get().data
        assertEquals(200, request("/events", "pine").statusCode())
        assertEquals(200, request("/sweep").statusCode())
        assertEquals(before, firestore.document("notifications/pine_alice").get().get().data)
        assertEquals(2, firestore.collection("offers").get().get().size())
        assertEquals(2, firestore.collection("notifications").get().get().size())
    }

    /** A timer catches a deadline even when no Firestore event is delivered after registration ends. */
    @Test fun `scheduled sweep catches overdue deadlines and preserves future deadlines`() {
        seedLottery("due", -1)
        seedLottery("future", 3600)
        assertEquals(200, request("/sweep").statusCode())
        assertWinningResult("due")
        assertEquals(false, firestore.document("lotteries/future").get().get().getBoolean("winnersDrawn"))
        assertFalse(firestore.document("offers/future_alice").get().get().exists())
    }

    /** An old event must read a deadline extension instead of drawing from its obsolete event payload. */
    @Test fun `delayed event after a deadline extension does not draw early`() {
        seedLottery("extended", -1)
        firestore.document("lotteries/extended").update("registrationEndAt", time(3600)).get()
        assertEquals(200, request("/events", "extended").statusCode())
        assertEquals(false, firestore.document("lotteries/extended").get().get().getBoolean("winnersDrawn"))
        assertEquals(0, firestore.collection("offers").get().get().size())
    }

    /** Failed processing is acknowledged as a failure and a later retry can finish the draw. */
    @Test fun `bad lottery reports failure and can be retried after correction`() {
        seedLottery("broken", -1)
        firestore.document("lotteries/broken").update("winnerCount", 0).get()
        assertEquals(500, request("/events", "broken").statusCode())
        assertEquals(false, firestore.document("lotteries/broken").get().get().getBoolean("winnersDrawn"))
        assertEquals(0, firestore.collection("notifications").get().get().size())
        firestore.document("lotteries/broken").update("winnerCount", 1).get()
        assertEquals(200, request("/events", "broken").statusCode())
        assertWinningResult("broken")
    }

    /** Eventarc and the timer may race, but only one atomic draw can publish results. */
    @Test fun `simultaneous event and sweep publish one winning result`() {
        seedLottery("race", -1)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val calls = listOf("/events", "/sweep").map { path -> pool.submit<Int> {
                check(start.await(5, TimeUnit.SECONDS))
                request(path, "race").statusCode()
            } }
            start.countDown()
            assertEquals(listOf(200, 200), calls.map { it.get(45, TimeUnit.SECONDS) })
        }
        assertWinningResult("race")
        assertEquals(1, firestore.collection("notifications").get().get().size())
        assertEquals(1, firestore.collection("offers").get().get().size())
    }

    /** An Inbox write conflict must also roll back the draw, offer, and push event. */
    @Test fun `existing inbox item prevents a partial draw without overwriting it`() {
        seedLottery("conflict", -1)
        val prior = mapOf("type" to "lottery_lost", "read" to true)
        firestore.document("notifications/conflict_alice").create(prior).get()
        assertEquals(500, request("/events", "conflict").statusCode())
        assertEquals(prior, firestore.document("notifications/conflict_alice").get().get().data)
        assertEquals(false, firestore.document("lotteries/conflict").get().get().getBoolean("winnersDrawn"))
        assertFalse(firestore.document("offers/conflict_alice").get().get().exists())
        assertFalse(firestore.document("lotteryNotificationOutbox/conflict_alice").get().get().exists())
    }

    /** Rejects events from a different project or collection before processing any lottery. */
    @Test fun `invalid cloud events cannot invoke a draw`() {
        seedLottery("due", -1)
        assertEquals(400, request("/events", "due", sourceProject = "another-project").statusCode())
        assertEquals(400, request("/events", "due/child").statusCode())
        assertEquals(false, firestore.document("lotteries/due").get().get().getBoolean("winnersDrawn"))
    }

    /** Sends a binary CloudEvent envelope or timer request to the real HTTP handler. */
    private fun request(path: String, lotteryId: String = "", sourceProject: String = projectId): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .header("ce-type", "google.cloud.firestore.document.v1.written")
            .header("ce-source", "//firestore.googleapis.com/projects/$sourceProject/databases/(default)")
            .header("ce-subject", "documents/lotteries/$lotteryId")
            .header("Content-Type", "application/protobuf")
            .timeout(java.time.Duration.ofSeconds(45))
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Creates an entrant and a lottery whose booking window remains open after the draw. */
    private fun seedLottery(id: String, deadlineOffsetSeconds: Long) {
        firestore.document("lotteries/$id").create(mapOf(
            "status" to "open", "winnersDrawn" to false, "registrationEndAt" to time(deadlineOffsetSeconds),
            "bookingEndAt" to time(86400), "winnerCount" to 1, "entrantCount" to 1,
            "campsiteIds" to listOf("camp-$id"),
        )).get()
        firestore.document("waitlist/${id}_alice").create(mapOf(
            "lotteryId" to id, "userId" to "alice", "status" to "joined",
        )).get()
    }

    /** Confirms that the drawn flag, winner, offer, and visible Inbox result committed together. */
    private fun assertWinningResult(id: String) {
        val lottery = firestore.document("lotteries/$id").get().get()
        val offer = firestore.document("offers/${id}_alice").get().get()
        val inbox = firestore.document("notifications/${id}_alice").get().get()
        assertEquals(true, lottery.getBoolean("winnersDrawn"))
        assertEquals("won", firestore.document("waitlist/${id}_alice").get().get().getString("status"))
        assertEquals("pending", offer.getString("status"))
        assertTrue(offer.getTimestamp("expiresAt")!! > Timestamp.now())
        assertEquals(listOf("camp-$id"), offer.get("campsiteIds"))
        assertEquals("lottery_won", inbox.getString("type"))
        assertEquals("${id}_alice", inbox.getString("offerId"))
        assertEquals("alice", inbox.getString("toUserId"))
        assertEquals(lottery.getTimestamp("updatedAt"), inbox.getTimestamp("createdAt"))
        assertEquals(offer.getTimestamp("createdAt"), inbox.getTimestamp("createdAt"))
    }

    /** Provides real backend timestamps relative to the request time. */
    private fun time(offsetSeconds: Long): Timestamp = Timestamp.now().let {
        Timestamp.ofTimeSecondsAndNanos(it.seconds + offsetSeconds, it.nanos)
    }
}
