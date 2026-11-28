/* BookingAutomationServerTest.kt: real HTTP booking events, timer recovery, validation, and retries. */
package com.example.legion_events.worker

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BookingAutomationServerTest : BookingWorkerFixture() {
    private lateinit var server: BookingAutomationServer
    private val client = HttpClient.newHttpClient()
    @Before fun listen() {
        server = BookingAutomationServer(firestore, projectId, InetSocketAddress("127.0.0.1", 0))
        server.start()
    }
    @After fun stop() { if (::server.isInitialized) server.close(); client.close() }

    @Test fun acceptanceWriteEventConfirmsOnceAndRepeatedOutOfOrderEventsPreserveTheResult() {
        seedRequest()
        assertEquals(200, request("/events", "reservations/booking"))
        val saved = data("reservations/booking")
        assertEquals("confirmed", saved["status"])
        assertEquals(200, request("/events", "reservations/booking"))
        assertEquals(200, request("/events", "offers/draw_alice"))
        assertEquals(200, request("/sweep"))
        assertEquals(saved, data("reservations/booking"))
        assertEquals(2, locks().size)
        assertEquals(1, firestore.collection("notifications").get().get().size())
    }
    @Test fun timerRecoversMissedAcceptanceAndOwnerRejectionEvents() {
        seedRequest()
        assertEquals(200, request("/sweep"))
        update("reservations/booking", mapOf("status" to "rejected"))
        assertEquals(200, request("/sweep"))
        assertTrue(locks().isEmpty())
        assertTrue(exists("reservationReleases/booking"))
    }
    @Test fun declinedOfferEventPublishesOneReplacementWithoutDrawingTheLotteryAgain() {
        seedRequest()
        firestore.document("reservations/booking").delete().get()
        update("offers/draw_alice", mapOf("response" to "decline", "reservationId" to null))
        put("users/bob", mapOf("disabled" to false))
        put("waitlist/draw_bob", mapOf("lotteryId" to "draw", "userId" to "bob", "status" to "lost"))
        val lottery = data("lotteries/draw")
        assertEquals(200, request("/events", "offers/draw_alice"))
        assertEquals("bob", data("offerReplacements/draw_alice")["replacementUserId"])
        assertEquals(200, request("/events", "offers/draw_alice"))
        assertEquals(lottery, data("lotteries/draw"))
        assertEquals(2, firestore.collection("offers").get().get().size())
    }
    @Test fun invalidEventSourcePathTypeAndHttpMethodCannotProcessBookings() {
        seedRequest()
        assertEquals(400, request("/events", "reservations/booking", source = "another-project"))
        assertEquals(400, request("/events", "lotteries/draw"))
        assertEquals(400, request("/events", "reservations/booking/nested"))
        assertEquals(400, request("/events", "reservations/booking", type = "wrong-type"))
        assertEquals(405, request("/sweep", method = "GET"))
        assertEquals(404, request("/unknown"))
        assertEquals("requested", data("reservations/booking")["status"])
        assertTrue(locks().isEmpty())
    }
    @Test fun failedEventRollsBackAndCanBeRetriedAfterTheConflictIsResolved() {
        seedRequest()
        put("notifications/reservation_confirmed_booking", mapOf("conflict" to true))
        assertEquals(500, request("/events", "reservations/booking"))
        assertTrue(locks().isEmpty())
        assertEquals("requested", data("reservations/booking")["status"])
        firestore.document("notifications/reservation_confirmed_booking").delete().get()
        assertEquals(200, request("/events", "reservations/booking"))
        assertEquals("confirmed", data("reservations/booking")["status"])
    }
    @Test fun failedConfirmationStageDoesNotStopIndependentRejectionCleanup() {
        seedRequest("broken", fields = mapOf("startDate" to "bad date"))
        seedRequest("rejected", "bob", mapOf("status" to "rejected"))
        assertEquals(500, request("/sweep"))
        assertTrue(exists("reservationReleases/rejected"))
        assertNull(data("offers/draw_bob")["response"])
    }
    /** Sends verified or intentionally malformed headers to a loopback HTTP server. */
    private fun request(path: String, document: String = "", source: String = projectId,
        type: String = "google.cloud.firestore.document.v1.written", method: String = "POST"): Int {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .header("ce-type", type).header("ce-source", "//firestore.googleapis.com/projects/$source/databases/(default)")
            .header("ce-subject", "documents/$document").timeout(Duration.ofSeconds(45))
            .method(method, HttpRequest.BodyPublishers.noBody()).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }
}
