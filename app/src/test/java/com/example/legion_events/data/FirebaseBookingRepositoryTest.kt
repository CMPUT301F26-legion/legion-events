/* FirebaseBookingRepositoryTest.kt: booking ownership, atomic acceptance payloads, and recovery. */
package com.example.legion_events.data

import com.example.legion_events.model.*
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

class FirebaseBookingRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val transaction = mock(Transaction::class.java)
    private val start = Timestamp(1_815_696_000, 0)
    private val end = Timestamp(start.seconds + 2 * 86_400, 0)
    private val at = Timestamp(start.seconds - 10 * 86_400, 0)
    private val deadline = Timestamp(at.seconds + 86_400, 0)
    private val refs = mutableMapOf<String, DocumentReference>()
    private val collections = mutableMapOf<String, CollectionReference>()
    private val documents = mutableMapOf<String, DocumentSnapshot>()
    private val requestQuery = mock(Query::class.java)
    private val lockQuery = mock(Query::class.java)
    private var savedRequests = emptyList<DocumentSnapshot>()
    private var locks = emptyList<DocumentSnapshot>()
    private var failReadAfterCommit = false
    private var writes = 0
    private val repository = FirebaseBookingRepository(auth, firestore) { at }
    private val request = BookingRequest("attempt", "draw_entrant", "pine", start, end, 2, 3000,
        BookingTerms(listOf("Quiet hours"), "No refunds"), true)
    private val offerFields get() = mapOf("userId" to "entrant", "lotteryId" to "draw", "status" to "pending",
        "response" to null, "campsiteIds" to listOf("pine"), "expiresAt" to deadline)
    private val campFields get() = mapOf("ownerId" to "owner", "active" to true, "name" to "Pine", "locationName" to "Banff",
        "maxGuests" to 4L, "pricePerNight" to 1500L, "rules" to "Quiet hours", "cancellationPolicy" to "No refunds",
        "availableNights" to bookingNightKeys(start, end))

    @Before fun setup() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("entrant")
        for (name in listOf("offers", "lotteries", "campsites", "bookedNights", "reservations")) {
            val collection = mock(CollectionReference::class.java)
            collections[name] = collection
            `when`(firestore.collection(name)).thenReturn(collection)
        }
        for (path in listOf("offers/draw_entrant", "lotteries/draw", "campsites/pine", "reservations/attempt")) {
            val (collection, id) = path.split('/')
            val ref = mock(DocumentReference::class.java)
            refs[path] = ref
            `when`(collections.getValue(collection).document(id)).thenReturn(ref)
            `when`(ref.get(Source.SERVER)).thenAnswer { Tasks.forResult(documents.getValue(path)) }
            `when`(transaction.get(ref)).thenAnswer { documents.getValue(path) }
        }
        documents["offers/draw_entrant"] = document("draw_entrant", offerFields)
        documents["lotteries/draw"] = document("draw", mapOf("ownerId" to "owner", "campsiteIds" to listOf("pine"),
            "registrationStartAt" to Timestamp(1, 0), "registrationEndAt" to Timestamp(2, 0),
            "bookingStartAt" to Timestamp(at.seconds - 1, 0), "bookingEndAt" to deadline,
            "winnerCount" to 1L, "winnersDrawn" to true))
        documents["campsites/pine"] = document("pine", campFields)
        val uidQuery = mock(Query::class.java)
        `when`(collections.getValue("reservations").whereEqualTo("userId", "entrant")).thenReturn(uidQuery)
        `when`(uidQuery.whereEqualTo("offerId", "draw_entrant")).thenReturn(requestQuery)
        `when`(requestQuery.get(Source.SERVER)).thenAnswer {
            if (failReadAfterCommit && writes > 0) {
                failReadAfterCommit = false
                Tasks.forException<QuerySnapshot>(java.io.IOException("lost response"))
            } else Tasks.forResult(snapshot(savedRequests))
        }
        `when`(collections.getValue("bookedNights").whereEqualTo("campsiteId", "pine")).thenReturn(lockQuery)
        `when`(lockQuery.get(Source.SERVER)).thenAnswer { Tasks.forResult(snapshot(locks)) }
        doAnswer { call ->
            val function = call.getArgument<Transaction.Function<Unit>>(0)
            try { function.apply(transaction); Tasks.forResult(Unit) } catch (e: Exception) { Tasks.forException<Unit>(e) }
        }.`when`(firestore).runTransaction<Unit>(any())
        doAnswer { call ->
            val fields = call.getArgument<Map<String, Any>>(1).mapValues { (key, value) ->
                if (key in listOf("createdAt", "updatedAt")) at else if (value is Int) value.toLong() else value
            }
            writes++
            val saved = document("attempt", fields)
            savedRequests = listOf(saved)
            documents["reservations/attempt"] = saved
            transaction
        }.`when`(transaction).set(eq(refs.getValue("reservations/attempt")), anyMap<String, Any>())
    }

    @Test fun optionsSubtractCanonicalLocksAndExposeCurrentTerms() = runTest {
        locks = listOf(document("pine_2027-07-16", mapOf("campsiteId" to "pine", "reservationId" to "other", "date" to "2027-07-16")))
        val options = repository.getBookingOptions("draw_entrant")
        assertEquals(setOf("2027-07-17"), options.campsites.single().availableNightKeys)
        assertEquals(request.terms, BookingTerms.from(options.campsites.single().campsite))
        assertTrue(options.canAccept(at))
        verifyNoInteractions(transaction)
    }
    @Test fun acceptedRequestCreatesOnlyPendingStateAndTheMatchingOfferClaim() = runTest {
        val saved = repository.acceptOffer(request)
        assertEquals(Reservation.STATUS_REQUESTED, saved.status)
        assertEquals(request.startDate, saved.startDate)
        assertEquals(3000L, saved.totalPrice)
        assertEquals("entrant", saved.userId)
        assertEquals("owner", saved.ownerId)
        verify(transaction).update(refs.getValue("offers/draw_entrant"), mapOf("response" to "accept", "reservationId" to "attempt",
            "respondedAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        verify(firestore, never()).collection("notifications")
        verify(firestore, never()).collection("bookedNights")
    }
    @Test fun unavailableSitesDoNotPreventReadingTheRemainingChoices() = runTest {
        documents["campsites/pine"] = document("pine", campFields + mapOf("active" to false))
        assertTrue(repository.getBookingOptions("draw_entrant").campsites.isEmpty())
        verify(lockQuery, never()).get(Source.SERVER)
    }
    @Test fun malformedLocksAndImpossibleListedDatesFailWithoutInventingAvailability() = runTest {
        locks = listOf(document("pine_2027-07-16", mapOf("campsiteId" to "pine", "date" to "2027-07-16")))
        failure<IllegalStateException> { repository.getBookingOptions("draw_entrant") }
        locks = emptyList()
        documents["campsites/pine"] = document("pine", campFields + mapOf("availableNights" to listOf("2027-02-30")))
        failure<IllegalStateException> { repository.getBookingOptions("draw_entrant") }
    }
    @Test fun successfulCommitWithLostResponseRecoversWithoutASecondWrite() = runTest {
        failReadAfterCommit = true
        failure<java.io.IOException> { repository.acceptOffer(request) }
        val recovered = repository.acceptOffer(request)
        assertEquals("attempt", recovered.id)
        assertEquals(1, writes)
    }
    @Test fun repeatedIdCannotChangePreviouslyAcceptedDetails() = runTest {
        repository.acceptOffer(request)
        failure<IllegalStateException> { repository.acceptOffer(request.copy(guestCount = 3)) }
        assertEquals(1, writes)
    }
    @Test fun transactionRecoversAConcurrentSuccessfulSubmission() = runTest {
        repository.acceptOffer(request)
        savedRequests = emptyList() // Emulate a query started before a competing commit.
        documents["offers/draw_entrant"] = document("draw_entrant", offerFields + mapOf("response" to "accept", "reservationId" to "attempt"))
        // The final recovery query sees the committed request.
        var reads = 0
        `when`(requestQuery.get(Source.SERVER)).thenAnswer {
            reads++
            Tasks.forResult(snapshot(if (reads == 1) emptyList() else listOf(documents.getValue("reservations/attempt"))))
        }
        assertEquals("attempt", repository.acceptOffer(request).id)
        assertEquals(1, writes)
    }
    @Test fun occupiedOfferCannotReceiveAnotherRequest() = runTest {
        documents["offers/draw_entrant"] = document("draw_entrant", offerFields + mapOf("response" to "accept", "reservationId" to "other-attempt"))
        failure<IllegalStateException> { repository.acceptOffer(request) }
        assertEquals(0, writes)
    }
    @Test fun wrongOwnerAndLegacyIncompleteOffersFailBeforeBooking() = runTest {
        documents["offers/draw_entrant"] = document("draw_entrant", offerFields + mapOf("userId" to "other"))
        failure<SecurityException> { repository.getBookingOptions("draw_entrant") }
        failure<SecurityException> { repository.acceptOffer(request) }
        documents["offers/draw_entrant"] = document("draw_entrant", offerFields - "campsiteIds")
        failure<IllegalStateException> { repository.acceptOffer(request) }
        assertEquals(0, writes)
    }
    @Test fun changedPoliciesPricesCapacityOrAvailabilityRequireNewReview() = runTest {
        for (changes in listOf(mapOf("rules" to "New rules"), mapOf("cancellationPolicy" to "New policy"),
            mapOf("pricePerNight" to 1600L), mapOf("maxGuests" to 1L), mapOf("availableNights" to emptyList<String>()),
            mapOf("active" to false), mapOf("ownerId" to "other"))) {
            documents["campsites/pine"] = document("pine", campFields + changes)
            failure<Exception> { repository.acceptOffer(request) }
        }
        assertEquals(0, writes)
    }
    @Test fun invalidPathsAndUnacceptedPoliciesNeverReachFirestore() = runTest {
        clearInvocations(firestore)
        failure<IllegalArgumentException> { repository.acceptOffer(request.copy(rulesAccepted = false)) }
        failure<IllegalArgumentException> { repository.getBookingOptions("other/path") }
        failure<IllegalArgumentException> { repository.getRequestsForOffer(" ") }
        verifyNoInteractions(firestore)
    }
    @Test fun signedOutAccessAndChangedAccountsAreDenied() = runTest {
        `when`(auth.currentUser).thenReturn(null)
        failure<IllegalStateException> { repository.acceptOffer(request) }
        failure<IllegalStateException> { repository.getBookingOptions("draw_entrant") }
        `when`(auth.currentUser).thenReturn(user)
        `when`(requestQuery.get(Source.SERVER)).thenAnswer {
            `when`(auth.currentUser).thenReturn(null)
            Tasks.forResult(snapshot(emptyList()))
        }
        failure<SecurityException> { repository.acceptOffer(request) }
        assertEquals(0, writes)
    }
    @Test fun expiredAndUnopenedOffersCannotBeAccepted() = runTest {
        documents["offers/draw_entrant"] = document("draw_entrant", offerFields + mapOf("expiresAt" to at))
        failure<IllegalStateException> { repository.acceptOffer(request) }
        assertEquals(0, writes)
    }
    private fun document(id: String, fields: Map<String, Any?>): DocumentSnapshot = mock(DocumentSnapshot::class.java).also { d ->
        `when`(d.id).thenReturn(id)
        `when`(d.exists()).thenReturn(true)
        `when`(d.get(anyString())).thenAnswer { fields[it.getArgument<String>(0)] }
        `when`(d.getString(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? String }
        `when`(d.getLong(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Long }
        `when`(d.getTimestamp(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Timestamp }
        `when`(d.getBoolean(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Boolean }
    }
    private fun snapshot(documents: List<DocumentSnapshot>): QuerySnapshot = mock(QuerySnapshot::class.java).also {
        `when`(it.documents).thenReturn(documents)
    }
    private suspend inline fun <reified T : Throwable> failure(crossinline action: suspend () -> Unit) {
        try { action() } catch (e: Throwable) { if (e is T) return else throw e }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
