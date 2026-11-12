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
    private val repository = FirebaseReservationRepository(auth, firestore)

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

    @Test fun permissionAndNetworkFailuresPropagateForTheControllerToHandle() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(query.get()).thenReturn(Tasks.forException(denied))
        assertSame(denied, failure<FirebaseFirestoreException> { repository.getMyReservations() })
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
