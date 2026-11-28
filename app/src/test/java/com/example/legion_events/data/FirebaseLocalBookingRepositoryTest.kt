package com.example.legion_events.data

import com.example.legion_events.model.bookingNightKeys
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/** Executes the actual Android transaction callbacks against SDK-boundary fakes. */
class FirebaseLocalBookingRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val tx = mock(Transaction::class.java)
    private val references = mutableMapOf<String, DocumentReference>()
    private val documents = mutableMapOf<String, MutableMap<String, Any?>>()
    private val writes = linkedMapOf<String, Map<String, Any?>>()
    private val deletes = mutableSetOf<String>()
    private val created = Timestamp(1_800_000_000, 0)
    private val start = Timestamp((created.seconds / 86400 + 1) * 86400, 0)
    private val end = Timestamp(start.seconds + 2 * 86400, 0)
    private val expiry = Timestamp(created.seconds + 86400, 0)
    private val nights = bookingNightKeys(start, end)
    private lateinit var repository: FirebaseLocalBookingRepository

    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("owner")
        `when`(firestore.collection(anyString())).thenAnswer { collection(it.getArgument(0)) }
        `when`(tx.get(any(DocumentReference::class.java))).thenAnswer { snapshot(it.getArgument<DocumentReference>(0).path) }
        `when`(tx.set(any(DocumentReference::class.java), anyMap<String, Any?>())).thenAnswer {
            writes[it.getArgument<DocumentReference>(0).path] = it.getArgument(1); tx
        }
        `when`(tx.update(any(DocumentReference::class.java), anyMap<String, Any?>())).thenAnswer {
            writes[it.getArgument<DocumentReference>(0).path] = it.getArgument(1); tx
        }
        `when`(tx.delete(any(DocumentReference::class.java))).thenAnswer { deletes += it.getArgument<DocumentReference>(0).path; tx }
        `when`(firestore.runTransaction<Boolean>(any())).thenAnswer {
            try { Tasks.forResult(it.getArgument<Transaction.Function<Boolean>>(0).apply(tx)) }
            catch (error: Exception) { Tasks.forException<Boolean>(error) }
        }
        documents["reservations/request"] = mutableMapOf("userId" to "alice", "ownerId" to "owner", "lotteryId" to "draw",
            "offerId" to "draw_alice", "campsiteId" to "camp", "createdAt" to created, "startDate" to start, "endDate" to end,
            "guestCount" to 2L, "totalPrice" to 3000L, "rulesAccepted" to true, "status" to "requested", "cancellationRequested" to false)
        documents["offers/draw_alice"] = mutableMapOf("userId" to "alice", "lotteryId" to "draw", "campsiteIds" to listOf("camp"),
            "status" to "pending", "response" to "accept", "reservationId" to "request", "respondedAt" to created, "expiresAt" to expiry)
        documents["lotteries/draw"] = mutableMapOf("ownerId" to "owner", "campsiteIds" to listOf("camp"), "winnersDrawn" to true,
            "bookingStartAt" to Timestamp(1, 0), "bookingEndAt" to expiry)
        documents["campsites/camp"] = mutableMapOf("ownerId" to "owner", "active" to true, "maxGuests" to 4L,
            "pricePerNight" to 1500L, "availableNights" to nights)
        repository = FirebaseLocalBookingRepository(auth, firestore) { created }
    }

    @Test fun confirmationCommitsAllLocksOfferAndReceiptTogether() = runTest {
        assertTrue(repository.confirm("request", "owner"))
        assertEquals("confirmed", writes.getValue("reservations/request")["status"])
        assertEquals("used", writes.getValue("offers/draw_alice")["status"])
        assertEquals("request", writes.getValue("notifications/reservation_confirmed_request")["reservationId"])
        assertEquals(nights.toSet(), writes.filterKeys { it.startsWith("bookedNights/") }.values.map { it["date"] }.toSet())
        assertEquals(5, writes.size)
    }

    @Test fun malformedRequestDoesNotBlockAnIndependentValidAcceptance() = runTest {
        documents["reservations/broken"] = documents.getValue("reservations/request").toMutableMap().apply {
            put("userId", "bob"); put("offerId", "draw_bob"); put("startDate", Timestamp(start.seconds + 1, 0))
        }
        documents["offers/draw_bob"] = documents.getValue("offers/draw_alice").toMutableMap().apply {
            put("userId", "bob"); put("reservationId", "broken")
        }
        val result = repository.process("owner")
        assertEquals(1, result.completed)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().message!!.contains("broken"))
        assertEquals("confirmed", writes.getValue("reservations/request")["status"])
        assertFalse(writes.containsKey("reservations/broken"))
    }

    @Test fun occupiedNightRejectsOnlyThisAttemptAndPreservesUnexpiredOffer() = runTest {
        documents["bookedNights/camp_${nights[0]}"] = mutableMapOf("reservationId" to "other", "campsiteId" to "camp")
        assertTrue(repository.confirm("request", "owner"))
        assertEquals(setOf("reservations/request", "offers/draw_alice"), writes.keys)
        assertEquals("rejected", writes.getValue("reservations/request")["status"])
        assertNull(writes.getValue("offers/draw_alice")["response"])
        assertFalse(writes.getValue("offers/draw_alice").containsKey("expiresAt"))
    }

    @Test fun changedCapacityPriceAndListedNightsCannotBecomeBookings() = runTest {
        for ((field, value) in listOf("maxGuests" to 1L, "pricePerNight" to 1L, "availableNights" to emptyList<String>())) {
            val original = documents.getValue("campsites/camp")[field]
            documents.getValue("campsites/camp")[field] = value
            writes.clear()
            repository.confirm("request", "owner")
            assertEquals("rejected", writes.getValue("reservations/request")["status"])
            assertFalse(writes.keys.any { it.startsWith("bookedNights/") || it.startsWith("notifications/") })
            documents.getValue("campsites/camp")[field] = original
        }
    }

    @Test fun staleDuplicateAndDeclinedEventsNeverConfirm() = runTest {
        documents.getValue("offers/draw_alice")["response"] = "decline"
        assertFalse(repository.confirm("request", "owner"))
        documents.getValue("reservations/request")["status"] = "confirmed"
        assertFalse(repository.confirm("request", "owner"))
        assertTrue(writes.isEmpty())
    }

    @Test fun malformedDatesAndWrongOwnersFailWithoutWrites() = runTest {
        documents.getValue("reservations/request")["startDate"] = Timestamp(start.seconds + 1, 0)
        try { repository.confirm("request", "owner"); fail("Expected invalid dates") } catch (_: IllegalArgumentException) {}
        documents.getValue("reservations/request")["ownerId"] = "foreign"
        try { repository.confirm("request", "owner"); fail("Expected owner check") } catch (_: IllegalStateException) {}
        assertTrue(writes.isEmpty())
    }

    @Test fun cancellationReleasesOnlyMatchingNightsAndCreatesReceipt() = runTest {
        documents.getValue("reservations/request").putAll(mapOf("status" to "confirmed", "cancellationRequested" to true))
        documents["bookedNights/camp_${nights[0]}"] = mutableMapOf("reservationId" to "request", "campsiteId" to "camp")
        documents["bookedNights/camp_${nights[1]}"] = mutableMapOf("reservationId" to "new-booking", "campsiteId" to "camp")
        assertTrue(repository.release("request", "owner"))
        assertEquals(setOf("bookedNights/camp_${nights[0]}"), deletes)
        assertEquals("cancelled", writes.getValue("reservations/request")["status"])
        assertEquals(false, writes.getValue("reservations/request")["cancellationRequested"])
        assertTrue(writes.containsKey("notifications/reservation_cancelled_request"))
    }

    @Test fun rejectedOldAttemptDoesNotClearANewerAcceptanceOrNotifyTwice() = runTest {
        documents.getValue("reservations/request")["status"] = "rejected"
        documents.getValue("offers/draw_alice")["reservationId"] = "new-attempt"
        assertTrue(repository.release("request", "owner"))
        assertEquals(setOf("reservationReleases/request"), writes.keys)
        documents["reservationReleases/request"] = mutableMapOf("reservationId" to "request")
        writes.clear()
        assertFalse(repository.release("request", "owner"))
        assertTrue(writes.isEmpty())
    }

    @Test fun replacementWritesOneOfferWithoutChangingLotteryOrOriginalWinners() = runTest {
        documents.getValue("offers/draw_alice").apply { put("response", "decline"); remove("reservationId") }
        documents["waitlist/draw_bob"] = mutableMapOf("lotteryId" to "draw", "userId" to "bob", "status" to "lost")
        assertTrue(repository.replace("draw_alice", "owner"))
        assertEquals("draw_alice", writes.getValue("offers/draw_bob")["replacementFor"])
        assertEquals(expiry, writes.getValue("offers/draw_bob")["expiresAt"])
        assertEquals("bob", writes.getValue("offerReplacements/draw_alice")["replacementUserId"])
        assertEquals(setOf("offers/draw_bob", "waitlist/draw_bob", "offerReplacements/draw_alice",
            "notifications/lottery_replacement_draw_alice", "lotteryNotificationOutbox/lottery_replacement_draw_alice"), writes.keys)
    }

    @Test fun competingReplacementDoesNotSelectAnAlreadyWonEntry() = runTest {
        documents.getValue("offers/draw_alice").apply { put("response", "decline"); remove("reservationId") }
        // Simulate query seeing lost, then a different owner device changing it before transaction reads.
        documents["waitlist/draw_bob"] = mutableMapOf("lotteryId" to "draw", "userId" to "bob", "status" to "lost")
        doAnswer {
            val path = it.getArgument<DocumentReference>(0).path
            if (path == "waitlist/draw_bob") documents.getValue(path)["status"] = "won"
            snapshot(path)
        }.`when`(tx).get(any(DocumentReference::class.java))
        assertFalse(repository.replace("draw_alice", "owner"))
        assertTrue(writes.isEmpty())
    }

    private fun reference(path: String): DocumentReference = references.getOrPut(path) {
        mock(DocumentReference::class.java).also { ref ->
            `when`(ref.path).thenReturn(path)
            `when`(ref.id).thenReturn(path.substringAfterLast('/'))
            `when`(ref.get(Source.SERVER)).thenAnswer { Tasks.forResult(snapshot(path)) }
            `when`(ref.collection(anyString())).thenAnswer { collection("$path/${it.getArgument<String>(0)}") }
        }
    }
    private fun snapshot(path: String): DocumentSnapshot {
      val ref = reference(path)
      return mock(DocumentSnapshot::class.java).also { snap ->
        `when`(snap.exists()).thenReturn(path in documents)
        `when`(snap.id).thenReturn(path.substringAfterLast('/'))
        `when`(snap.reference).thenReturn(ref)
        `when`(snap.get(anyString())).thenAnswer { documents[path]?.get(it.getArgument<String>(0)) }
      }
    }
    private fun collection(path: String): CollectionReference = mock(CollectionReference::class.java).also { col ->
        `when`(col.document(anyString())).thenAnswer { reference("$path/${it.getArgument<String>(0)}") }
        `when`(col.whereEqualTo(anyString(), any())).thenAnswer { call ->
            val field = call.getArgument<String>(0)
            val value = call.getArgument<Any>(1)
            mock(Query::class.java).also { query ->
                `when`(query.limit(anyLong())).thenReturn(query)
                `when`(query.get(Source.SERVER)).thenAnswer {
                    val rows = documents.filter { (key, data) -> key.substringBeforeLast('/') == path && data[field] == value }.keys.map(::snapshot)
                    Tasks.forResult(mock(QuerySnapshot::class.java).also { `when`(it.documents).thenReturn(rows) })
                }
            }
        }
    }
}
