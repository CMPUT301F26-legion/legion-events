/*
 * FirebaseOwnerReservationRepositoryTest.kt
 *
 * Purpose: Verify S17's owner-scoped reads and the rejection transaction (US 02.07.01 to 02.07.03, 02.08.01).
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/** Checks the real implementation at mocked Firebase boundaries; never contacts Firestore. */
class FirebaseOwnerReservationRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val reservations = mock(CollectionReference::class.java)
    private val campsites = mock(CollectionReference::class.java)
    private val notices = mock(CollectionReference::class.java)
    private val byOwner = mock(Query::class.java)
    private val byCampsite = mock(Query::class.java)
    private val booking = mock(DocumentReference::class.java)
    private val campsite = mock(DocumentReference::class.java)
    private val notice = mock(DocumentReference::class.java)
    private val transaction = mock(Transaction::class.java)
    /** 2027-07-04T00:00:00Z in seconds. */
    private val july4 = 1_814_659_200L
    private val day = 24 * 60 * 60L
    /** Diagnostics the repository wrote, in order. */
    private val logged = mutableListOf<String>()
    private val repository = FirebaseOwnerReservationRepository(auth, firestore, { Timestamp(july4 - day, 0) }, logged::add)

    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("owner")
        `when`(firestore.collection("reservations")).thenReturn(reservations)
        `when`(firestore.collection("campsites")).thenReturn(campsites)
        `when`(firestore.collection("ownerNotices")).thenReturn(notices)
        `when`(reservations.whereEqualTo("ownerId", "owner")).thenReturn(byOwner)
        `when`(byOwner.whereEqualTo("campsiteId", "lake")).thenReturn(byCampsite)
        `when`(reservations.document("booking")).thenReturn(booking)
        `when`(campsites.document("lake")).thenReturn(campsite)
        `when`(notices.document()).thenReturn(notice)
        val site = mock(DocumentSnapshot::class.java)
        `when`(site.getString("name")).thenReturn("Two Jack Lakeside")
        `when`(transaction.get(campsite)).thenReturn(site)
        doAnswer { invocation ->
            try {
                invocation.getArgument<Transaction.Function<Unit>>(0).apply(transaction)
                Tasks.forResult(Unit)
            } catch (error: Exception) {
                Tasks.forException<Unit>(error)
            }
        }.`when`(firestore).runTransaction<Unit>(any())
    }

    @Test fun listingIsScopedToTheOwnerAndCampsiteAndNeverReadsPrivateProfiles() = runTest {
        returnsList(document("a"), document("b"), document("c", mapOf("userId" to "other")))
        val bookings = repository.getCampsiteReservations("lake")
        assertEquals(listOf("a", "b", "c"), bookings.map { it.id })
        assertEquals(7600L, bookings.first().totalPrice)
        verify(reservations).whereEqualTo("ownerId", "owner")
        verify(byOwner).whereEqualTo("campsiteId", "lake")
        // The rules let only the profile's owner or an admin read users/{uid}.
        verify(firestore, never()).collection("users")
    }

    @Test fun malformedOrForeignRecordsAreLeftOutInsteadOfHidingTheRest() = runTest {
        returnsList(document("good"), document("bad", mapOf("guestCount" to 0L)),
            document("foreign", mapOf("ownerId" to "someone-else")))
        assertEquals(listOf("good"), repository.getCampsiteReservations("lake").map { it.id })
        // Only the skipped record is logged; there is no per-query dump of booking IDs.
        assertEquals(listOf("Skipped reservation bad: Invalid guest count."), logged)
    }

    @Test fun signedOutAndInvalidRequestsNeverReachFirestore() = runTest {
        clearInvocations(firestore)
        for (id in listOf("", " ", "lake/nested")) {
            failure<IllegalArgumentException> { repository.getCampsiteReservations(id) }
            failure<IllegalArgumentException> { repository.rejectReservation(id, null) }
        }
        failure<IllegalArgumentException> { repository.rejectReservation("booking", "x".repeat(501)) }
        `when`(auth.currentUser).thenReturn(null)
        failure<IllegalStateException> { repository.getCampsiteReservations("lake") }
        failure<IllegalStateException> { repository.rejectReservation("booking", null) }
        verifyNoInteractions(firestore)
    }

    @Test fun anAccountChangeDuringTheReadReturnsNothing() = runTest {
        val result = mock(QuerySnapshot::class.java)
        val owned = document("a")
        `when`(result.documents).thenReturn(listOf(owned))
        `when`(byCampsite.get()).thenAnswer {
            `when`(auth.currentUser).thenReturn(null)
            Tasks.forResult(result)
        }
        failure<SecurityException> { repository.getCampsiteReservations("lake") }
    }

    @Test fun rejectionUpdatesOnlyStatusAndNotifiesTheEntrantWithoutTouchingBookedNights() = runTest {
        returnsInTransaction(document("booking"))
        repository.rejectReservation("booking", "  Fire ban  ")
        verify(transaction).update(booking, mapOf("status" to "rejected", "updatedAt" to FieldValue.serverTimestamp()))
        // bookedNights are worker-only under the rules.
        verify(firestore, never()).collection("bookedNights")
        verify(transaction, never()).delete(any(DocumentReference::class.java))
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(transaction).set(org.mockito.Mockito.eq(notice), captor.capture())
        val written = captor.value
        assertEquals("owner", written["ownerId"])
        assertEquals("lottery", written["lotteryId"])
        assertEquals(listOf("entrant"), written["recipientIds"])
        assertEquals("The owner rejected your reservation at Two Jack Lakeside for Jul 4, 2027 to Jul 7, 2027. " +
            "Reason: Fire ban", written["message"])
        assertEquals(setOf("ownerId", "lotteryId", "message", "recipientIds", "createdAt", "updatedAt"), written.keys)
    }

    @Test fun anotherOwnersBookingIsNeverWritten() = runTest {
        returnsInTransaction(document("booking", mapOf("ownerId" to "someone-else")))
        failure<SecurityException> { repository.rejectReservation("booking", null) }
        verifyNoWrites()
    }

    @Test fun acceptingConfirmsOnlyTheStatusAndNotifiesTheEntrantWithoutTouchingBookedNights() = runTest {
        returnsInTransaction(document("booking", mapOf("status" to "requested")))
        repository.acceptReservation("booking")
        verify(transaction).update(booking, mapOf("status" to "confirmed", "updatedAt" to FieldValue.serverTimestamp()))
        verify(firestore, never()).collection("bookedNights")
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(transaction).set(org.mockito.Mockito.eq(notice), captor.capture())
        assertEquals(listOf("entrant"), captor.value["recipientIds"])
        assertEquals("The owner confirmed your reservation at Two Jack Lakeside for Jul 4, 2027 to Jul 7, 2027.",
            captor.value["message"])
    }

    @Test fun onlyUpcomingRequestedBookingsWithoutACancellationCanBeAccepted() = runTest {
        for (fields in listOf(
            mapOf("status" to "confirmed"), mapOf("status" to "rejected"), mapOf("status" to "cancelled"),
            mapOf("status" to "requested", "cancellationRequested" to true),
            mapOf("status" to "requested", "startDate" to Timestamp(july4 - 5 * day, 0),
                "endDate" to Timestamp(july4 - 2 * day, 0)),
        )) {
            returnsInTransaction(document("booking", fields))
            val error = failure<IllegalStateException> { repository.acceptReservation("booking") }
            assertEquals("Only requested reservations can be accepted.", error.message)
        }
        returnsInTransaction(document("booking", mapOf("status" to "requested", "ownerId" to "someone-else")))
        failure<SecurityException> { repository.acceptReservation("booking") }
        verifyNoWrites()
    }

    @Test fun aDeniedAcceptanceReportsItIsNotEnabled() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(denied.code).thenReturn(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        `when`(denied.message).thenReturn("PERMISSION_DENIED")
        doAnswer { Tasks.forException<Unit>(denied) }.`when`(firestore).runTransaction<Unit>(any())
        val error = failure<IllegalStateException> { repository.acceptReservation("booking") }
        assertEquals("Accepting reservations is not enabled for owners yet.", error.message)
        assertEquals(listOf("Accepting reservation booking was denied: PERMISSION_DENIED"), logged)
    }

    @Test fun upcomingRequestedBookingsCanBeRejected() = runTest {
        returnsInTransaction(document("booking", mapOf("status" to "requested")))
        repository.rejectReservation("booking", null)
        verify(transaction).update(booking, mapOf("status" to "rejected", "updatedAt" to FieldValue.serverTimestamp()))
    }

    @Test fun aDeniedRejectionReportsItIsNotEnabledAndLogsTheOriginalError() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(denied.code).thenReturn(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        `when`(denied.message).thenReturn("PERMISSION_DENIED: Missing or insufficient permissions.")
        doAnswer { Tasks.forException<Unit>(denied) }.`when`(firestore).runTransaction<Unit>(any())
        val error = failure<IllegalStateException> { repository.rejectReservation("booking", null) }
        assertEquals("Rejecting reservations is not enabled for owners yet.", error.message)
        assertEquals(listOf("Rejecting reservation booking was denied: " +
            "PERMISSION_DENIED: Missing or insufficient permissions."), logged)
    }

    @Test fun onlyActiveRequestedOrConfirmedBookingsCanBeRejected() = runTest {
        for (fields in listOf(
            mapOf("status" to "cancelled"), mapOf("status" to "rejected"), mapOf("status" to "pending"),
            mapOf("status" to "requested", "startDate" to Timestamp(july4 - 5 * day, 0),
                "endDate" to Timestamp(july4 - 2 * day, 0)),
            mapOf("startDate" to Timestamp(july4 - 5 * day, 0), "endDate" to Timestamp(july4 - 2 * day, 0)),
        )) {
            returnsInTransaction(document("booking", fields))
            val error = failure<IllegalStateException> { repository.rejectReservation("booking", null) }
            assertEquals("Only active reservations can be rejected.", error.message)
        }
        verifyNoWrites()
    }

    @Test fun aDeletedBookingIsReportedWithoutWriting() = runTest {
        val missing = mock(DocumentSnapshot::class.java)
        `when`(transaction.get(booking)).thenReturn(missing)
        val error = failure<IllegalStateException> { repository.rejectReservation("booking", null) }
        assertEquals("This reservation no longer exists.", error.message)
        verifyNoWrites()
    }

    /** Confirms a failed rejection changed nothing. */
    private fun verifyNoWrites() {
        verify(transaction, never()).update(any(DocumentReference::class.java), org.mockito.Mockito.anyMap<String, Any>())
        verify(transaction, never()).delete(any(DocumentReference::class.java))
        verify(transaction, never()).set(any(DocumentReference::class.java), any())
    }

    /**
     * Supplies the booking read inside the transaction.
     * @param document The stored booking.
     */
    private fun returnsInTransaction(document: DocumentSnapshot) {
        `when`(transaction.get(booking)).thenReturn(document)
    }

    /**
     * Supplies query results after their document mocks are fully initialized.
     * @param documents The stored bookings.
     */
    private fun returnsList(vararg documents: DocumentSnapshot) {
        val snapshot = mock(QuerySnapshot::class.java)
        `when`(snapshot.documents).thenReturn(documents.toList())
        `when`(byCampsite.get()).thenReturn(Tasks.forResult(snapshot))
    }

    /**
     * Builds a full schema record, letting each test replace selected stored values.
     * @param id Reservation ID.
     * @param overrides Fields to replace.
     * @return The mocked document.
     */
    private fun document(id: String, overrides: Map<String, Any?> = emptyMap()): DocumentSnapshot {
        val fields = mapOf<String, Any?>("lotteryId" to "lottery", "offerId" to "offer", "campsiteId" to "lake",
            "userId" to "entrant", "ownerId" to "owner", "startDate" to Timestamp(july4, 0),
            "endDate" to Timestamp(july4 + 3 * day, 0), "guestCount" to 2L, "totalPrice" to 7600L,
            "rulesAccepted" to true, "status" to "confirmed", "cancellationRequested" to false) + overrides
        return mock(DocumentSnapshot::class.java).also { document ->
            `when`(document.exists()).thenReturn(true)
            `when`(document.id).thenReturn(id)
            fields.forEach { (field, value) -> `when`(document.get(field)).thenReturn(value) }
        }
    }

    /**
     * Checks a failing suspend operation without hiding an unexpected exception type.
     * @param action The call expected to fail.
     * @return The thrown error.
     */
    private suspend inline fun <reified T : Throwable> failure(action: () -> Unit): T {
        try { action() } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
