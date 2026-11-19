/*
 * FirebaseReservationRepositoryTest.kt
 *
 * Purpose: Verify reservation ownership, document mapping, and read failures.
 * Role: Local JVM tests for the data layer using mocked Firebase SDK boundaries.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/** Checks the real read implementation at mocked Firebase boundaries; never contacts Firestore. */
class FirebaseReservationRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val reservations = mock(CollectionReference::class.java)
    private val campsites = mock(CollectionReference::class.java)
    private val query = mock(Query::class.java)
    private val booking = mock(DocumentReference::class.java)
    private val campsite = mock(DocumentReference::class.java)
    private val transaction = mock(Transaction::class.java)
    private var attempts = listOf(transaction)
    private val repository = FirebaseReservationRepository(auth, firestore) { Timestamp(150, 0) }

    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("alice")
        `when`(firestore.collection("reservations")).thenReturn(reservations)
        `when`(firestore.collection("campsites")).thenReturn(campsites)
        `when`(reservations.whereEqualTo("userId", "alice")).thenReturn(query)
        `when`(reservations.document("booking")).thenReturn(booking)
        `when`(campsites.document("pine")).thenReturn(campsite)
        val labels = mock(DocumentSnapshot::class.java)
        `when`(labels.get("name")).thenReturn("Pine Ridge")
        `when`(labels.get("locationName")).thenReturn("Banff")
        `when`(campsite.get()).thenReturn(Tasks.forResult(labels))
        doAnswer { invocation ->
            val function = invocation.getArgument<Transaction.Function<Unit>>(0)
            try { attempts.forEach { function.apply(it) }; Tasks.forResult(Unit) }
            catch (error: Exception) { Tasks.forException<Unit>(error) }
        }.`when`(firestore).runTransaction<Unit>(any())
    }

    @Test fun listingIsUidScopedAndReadsEachCampsiteLabelOnlyOnce() = runTest {
        returnsList(listOf(document(), document("second")))
        val entries = repository.getMyReservations()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.reservation.userId == "alice" && it.campsiteName == "Pine Ridge" })
        assertEquals(6550L, entries.first().reservation.totalPrice)
        verify(reservations).whereEqualTo("userId", "alice")
        verify(campsite, times(1)).get()
    }

    @Test fun signedOutReadsNeverAccessFirestore() = runTest {
        `when`(auth.currentUser).thenReturn(null)
        clearInvocations(firestore)
        failure<IllegalStateException> { repository.getMyReservations() }
        failure<IllegalStateException> { repository.getReservation("booking") }
        verifyNoInteractions(firestore)
    }

    @Test fun anotherEntrantsRecordIsDeniedBeforeItsCampsiteIsRead() = runTest {
        returnsReservation(document(overrides = mapOf("userId" to "bob")))
        failure<SecurityException> { repository.getReservation("booking") }
        verify(campsite, never()).get()
        returnsList(listOf(document(overrides = mapOf("userId" to "bob"))))
        failure<SecurityException> { repository.getMyReservations() }
    }

    @Test fun aDeletedReservationReturnsNullAndInvalidPathsAreRejected() = runTest {
        returnsReservation(mock(DocumentSnapshot::class.java))
        assertNull(repository.getReservation("booking"))
        for (id in listOf("", " ", "other/booking")) {
            failure<IllegalArgumentException> { repository.getReservation(id) }
        }
        verify(campsite, never()).get()
    }

    @Test fun invalidBookingFieldsAreNotDefaultedIntoConfirmedReservations() = runTest {
        for (fields in listOf(
            mapOf("startDate" to "2027-01-01"), mapOf("endDate" to Timestamp(50, 0)),
            mapOf("status" to null), mapOf("guestCount" to 0L), mapOf("guestCount" to 2.5),
            mapOf("totalPrice" to -1L), mapOf("totalPrice" to 65.50),
            mapOf("cancellationRequested" to "false"), mapOf("rulesAccepted" to null),
            mapOf("campsiteId" to "pine/nested/document"), mapOf("createdAt" to "yesterday"),
        )) {
            returnsReservation(document(overrides = fields))
            failure<IllegalStateException> { repository.getReservation("booking") }
        }
    }

    @Test fun unreadableCampsiteDoesNotHideTheSavedBooking() = runTest {
        returnsReservation(document())
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(campsite.get()).thenReturn(Tasks.forException(denied))
        val entry = checkNotNull(repository.getReservation("booking"))
        assertNull(entry.campsiteName)
        assertEquals(6550L, entry.reservation.totalPrice)
        assertEquals(Timestamp(100, 0), entry.reservation.startDate)
    }

    @Test fun deniedDetailReadsAreUnavailableWhileDeniedListsStillFail() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(denied.code).thenReturn(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        `when`(booking.get()).thenReturn(Tasks.forException(denied))
        assertNull(repository.getReservation("booking"))
        verify(campsite, never()).get()
        `when`(query.get()).thenReturn(Tasks.forException(denied))
        assertSame(denied, failure<FirebaseFirestoreException> { repository.getMyReservations() })
    }

    @Test fun detailNetworkFailuresStillPropagateSoRetryRemainsAvailable() = runTest {
        val unavailable = mock(FirebaseFirestoreException::class.java)
        `when`(unavailable.code).thenReturn(FirebaseFirestoreException.Code.UNAVAILABLE)
        `when`(booking.get()).thenReturn(Tasks.forException(unavailable))
        assertSame(unavailable, failure<FirebaseFirestoreException> { repository.getReservation("booking") })
        verify(campsite, never()).get()
    }

    @Test fun anAccountChangeDuringAReadDoesNotReturnThePreviousUsersData() = runTest {
        `when`(booking.get()).thenAnswer {
            `when`(auth.currentUser).thenReturn(null)
            Tasks.forResult(document())
        }
        failure<SecurityException> { repository.getReservation("booking") }
        verify(campsite, never()).get()
        `when`(auth.currentUser).thenReturn(user)
        val owned = document()
        val result = mock(QuerySnapshot::class.java)
        `when`(result.documents).thenReturn(listOf(owned))
        `when`(query.get()).thenAnswer {
            `when`(auth.currentUser).thenReturn(null)
            Tasks.forResult(result)
        }
        failure<SecurityException> { repository.getMyReservations() }
        verify(campsite, never()).get()
    }

    @Test fun cancellationWritesOnlyTheRequestFlagAndServerTimeThenReadsTheServerOutcome() = runTest {
        val owned = document()
        val pending = document(overrides = mapOf("cancellationRequested" to true))
        `when`(transaction.get(booking)).thenReturn(owned)
        `when`(booking.get(Source.SERVER)).thenReturn(Tasks.forResult(pending))
        val result = repository.requestCancellation("booking")
        assertEquals("confirmed", result.reservation.status)
        assertTrue(result.reservation.cancellationRequested)
        verify(transaction).update(booking, mapOf("cancellationRequested" to true, "updatedAt" to FieldValue.serverTimestamp()))
        verify(firestore, never()).collection("bookedNights")
        verify(firestore, never()).collection("notifications")
    }

    @Test fun repeatedPendingAndCompletedRequestsDoNotRewriteTheBooking() = runTest {
        for (fields in listOf(mapOf("cancellationRequested" to true),
            mapOf("status" to "cancelled", "cancellationRequested" to false))) {
            val saved = document(overrides = fields + mapOf("endDate" to Timestamp(120, 0)))
            `when`(transaction.get(booking)).thenReturn(saved)
            `when`(booking.get(Source.SERVER)).thenReturn(Tasks.forResult(saved))
            repeat(2) { repository.requestCancellation("booking") }
        }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun cancellationRejectsForeignMissingTerminalAndCheckedOutBookings() = runTest {
        val foreign = document(overrides = mapOf("userId" to "bob"))
        `when`(transaction.get(booking)).thenReturn(foreign)
        failure<SecurityException> { repository.requestCancellation("booking") }
        for (invalid in listOf(mock(DocumentSnapshot::class.java),
            document(overrides = mapOf("status" to "rejected")),
            document(overrides = mapOf("endDate" to Timestamp(150, 0))))) {
            `when`(transaction.get(booking)).thenReturn(invalid)
            failure<IllegalStateException> { repository.requestCancellation("booking") }
        }
        for (id in listOf("", "path/nested")) failure<IllegalArgumentException> { repository.requestCancellation(id) }
        `when`(auth.currentUser).thenReturn(null)
        failure<IllegalStateException> { repository.requestCancellation("booking") }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun uncertainCommittedRequestsAreSafeToRetry() = runTest {
        val owned = document()
        val pending = document(overrides = mapOf("cancellationRequested" to true))
        `when`(transaction.get(booking)).thenReturn(owned, pending)
        `when`(booking.get(Source.SERVER)).thenReturn(Tasks.forException(IllegalStateException("Unavailable")), Tasks.forResult(pending))
        failure<IllegalStateException> { repository.requestCancellation("booking") }
        assertTrue(repository.requestCancellation("booking").reservation.cancellationRequested)
        verify(transaction, times(1)).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun retriedTransactionsRecheckOwnershipAndAccountChangesBeforeWriting() = runTest {
        val owned = document()
        val foreign = document(overrides = mapOf("userId" to "bob"))
        val retried = mock(Transaction::class.java)
        attempts = listOf(transaction, retried)
        `when`(transaction.get(booking)).thenReturn(owned)
        `when`(retried.get(booking)).thenReturn(foreign)
        failure<SecurityException> { repository.requestCancellation("booking") }
        verify(retried, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
        clearInvocations(transaction)
        attempts = listOf(transaction)
        `when`(transaction.get(booking)).thenAnswer { `when`(auth.currentUser).thenReturn(null); owned }
        failure<SecurityException> { repository.requestCancellation("booking") }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    /** Supplies a completed document read after its fields have been stubbed. */
    private fun returnsReservation(document: DocumentSnapshot) {
        `when`(booking.get()).thenReturn(Tasks.forResult(document))
    }

    /** Supplies query results after their document mocks are fully initialized. */
    private fun returnsList(documents: List<DocumentSnapshot>) {
        val snapshot = mock(QuerySnapshot::class.java)
        `when`(snapshot.documents).thenReturn(documents)
        `when`(query.get()).thenReturn(Tasks.forResult(snapshot))
    }

    /** Supplies a full schema record, allowing each test to replace selected stored values. */
    private fun document(id: String = "booking", overrides: Map<String, Any?> = emptyMap()): DocumentSnapshot {
        val fields = mapOf<String, Any?>("lotteryId" to "lottery", "offerId" to "offer", "campsiteId" to "pine",
            "userId" to "alice", "ownerId" to "owner", "startDate" to Timestamp(100, 0), "endDate" to Timestamp(200, 0),
            "guestCount" to 4L, "totalPrice" to 6550L, "rulesAccepted" to true, "status" to "confirmed",
            "cancellationRequested" to false) + overrides
        return mock(DocumentSnapshot::class.java).also { document ->
            `when`(document.exists()).thenReturn(true)
            `when`(document.id).thenReturn(id)
            fields.forEach { (field, value) -> `when`(document.get(field)).thenReturn(value) }
        }
    }

    /** Checks a failing suspend operation without hiding an unexpected exception type. */
    private suspend inline fun <reified T : Throwable> failure(action: () -> Unit): T {
        try { action() } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
