/*
 * FirebaseRatingRepositoryTest.kt
 *
 * Purpose: Verify owned completed-stay checks and retry-safe rating creates and updates.
 * Role: Local JVM tests at the Firebase SDK boundary; no production access.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

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

/** Exercises real mapping and transaction callbacks using synthetic Firestore snapshots. */
class FirebaseRatingRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val stays = mock(CollectionReference::class.java)
    private val sites = mock(CollectionReference::class.java)
    private val ratings = mock(CollectionReference::class.java)
    private val stay = mock(DocumentReference::class.java)
    private val site = mock(DocumentReference::class.java)
    private val rating = mock(DocumentReference::class.java)
    private val transaction = mock(Transaction::class.java)
    private var attempts = listOf(transaction)
    private val repository = FirebaseRatingRepository(auth, firestore) { Timestamp(200, 0) }

    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("alice")
        `when`(firestore.collection("reservations")).thenReturn(stays)
        `when`(firestore.collection("campsites")).thenReturn(sites)
        `when`(firestore.collection("ratings")).thenReturn(ratings)
        `when`(stays.document("booking #1")).thenReturn(stay)
        `when`(sites.document("pine")).thenReturn(site)
        `when`(ratings.document("pine_alice")).thenReturn(rating)
        val booking = booking()
        val campsite = document(mapOf("active" to true, "name" to "Pine Ridge", "locationName" to "Banff"))
        val missing = document(exists = false)
        val saved = savedRating()
        `when`(stay.get()).thenReturn(Tasks.forResult(booking))
        `when`(site.get()).thenReturn(Tasks.forResult(campsite))
        `when`(rating.get()).thenReturn(Tasks.forResult(missing))
        `when`(rating.get(Source.SERVER)).thenReturn(Tasks.forResult(saved))
        `when`(transaction.get(stay)).thenReturn(booking)
        `when`(transaction.get(site)).thenReturn(campsite)
        `when`(transaction.get(rating)).thenReturn(missing)
        doAnswer { invocation ->
            val function = invocation.getArgument<Transaction.Function<Pair<DocumentReference, String>>>(0)
            try { Tasks.forResult(attempts.map { function.apply(it) }.last()) }
            catch (error: Exception) { Tasks.forException<Pair<DocumentReference, String>>(error) }
        }.`when`(firestore).runTransaction<Pair<DocumentReference, String>>(any())
    }

    @Test fun completedStayLoadsItsExistingCampsiteRatingAtTheExactCheckoutBoundary() = runTest {
        val saved = savedRating()
        `when`(rating.get()).thenReturn(Tasks.forResult(saved))
        val result = checkNotNull(repository.getForStay("booking #1"))
        assertEquals("Pine Ridge", result.stay.campsiteName)
        assertEquals("pine", result.rating!!.campsiteId)
        assertEquals("alice", result.rating.userId)
        assertEquals(4, result.rating.score)
        verify(ratings).document("pine_alice")
    }

    @Test fun ineligibleOrForeignStaysCannotBeLoadedOrSaved() = runTest {
        val cases = listOf(booking(mapOf("userId" to "bob")), booking(mapOf("endDate" to Timestamp(200, 1))),
            booking(mapOf("status" to "cancelled")), booking(mapOf("status" to "rejected")),
            booking(mapOf("status" to "requested")), booking(mapOf("cancellationRequested" to true)), document(exists = false))
        for (invalid in cases) {
            `when`(stay.get()).thenReturn(Tasks.forResult(invalid))
            `when`(transaction.get(stay)).thenReturn(invalid)
            assertNull(repository.getForStay("booking #1"))
            failure<IllegalStateException> { repository.saveForStay("booking #1", 4) }
        }
        verify(transaction, never()).set(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun inactiveOrMissingCampsitesCannotBeRated() = runTest {
        for (invalid in listOf(document(mapOf("active" to false)), document(exists = false))) {
            `when`(site.get()).thenReturn(Tasks.forResult(invalid))
            `when`(transaction.get(site)).thenReturn(invalid)
            assertNull(repository.getForStay("booking #1"))
            failure<IllegalStateException> { repository.saveForStay("booking #1", 4) }
        }
    }

    @Test fun firstSaveUsesOnlyTheExactRatingFieldsAndNeverWritesCampsiteAggregates() = runTest {
        assertEquals(4, repository.saveForStay("booking #1", 4).score)
        verify(transaction).set(rating, mapOf("campsiteId" to "pine", "userId" to "alice", "score" to 4,
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        verify(transaction, never()).set(eq(site), anyMap<String, Any>())
        verify(transaction, never()).update(eq(site), anyMap<String, Any>())
        verify(rating).get(Source.SERVER)
    }

    @Test fun updatingOneScorePreservesTheOriginalCreationTime() = runTest {
        val old = savedRating()
        val saved = savedRating(mapOf("score" to 2L, "updatedAt" to Timestamp(250, 0)))
        `when`(transaction.get(rating)).thenReturn(old)
        `when`(rating.get(Source.SERVER)).thenReturn(Tasks.forResult(saved))
        val result = repository.saveForStay("booking #1", 2)
        assertEquals(Timestamp(210, 0), result.createdAt)
        assertEquals(Timestamp(250, 0), result.updatedAt)
        verify(transaction).update(rating, mapOf("score" to 2, "updatedAt" to FieldValue.serverTimestamp()))
        verify(transaction, never()).set(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun sameScoreAndUncertainCommittedSavesDoNotRewriteOrDuplicateTheRating() = runTest {
        val missing = document(exists = false)
        val saved = savedRating()
        `when`(transaction.get(rating)).thenReturn(missing, saved)
        `when`(rating.get(Source.SERVER)).thenReturn(Tasks.forException(IllegalStateException("Response lost")), Tasks.forResult(saved))
        failure<IllegalStateException> { repository.saveForStay("booking #1", 4) }
        assertEquals(Timestamp(210, 0), repository.saveForStay("booking #1", 4).createdAt)
        verify(transaction, times(1)).set(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun invalidScoresPathsAndSignedOutCallsNeverReachFirestore() = runTest {
        clearInvocations(firestore)
        for (score in listOf(0, 6, Int.MAX_VALUE)) failure<IllegalArgumentException> { repository.saveForStay("booking #1", score) }
        for (id in listOf("", "nested/path")) failure<IllegalArgumentException> { repository.getForStay(id) }
        `when`(auth.currentUser).thenReturn(null)
        failure<IllegalStateException> { repository.getForStay("booking #1") }
        failure<IllegalStateException> { repository.saveForStay("booking #1", 3) }
        verifyNoInteractions(firestore)
    }

    @Test fun malformedOrMismatchedRatingsAreNotSilentlyOverwritten() = runTest {
        for (fields in listOf(mapOf("score" to 0L), mapOf("score" to 6L), mapOf("score" to 2.5), mapOf("createdAt" to "today"))) {
            val invalid = savedRating(fields)
            `when`(rating.get()).thenReturn(Tasks.forResult(invalid))
            failure<IllegalStateException> { repository.getForStay("booking #1") }
        }
        for (fields in listOf(mapOf("userId" to "bob"), mapOf("campsiteId" to "other"))) {
            val invalid = savedRating(fields)
            `when`(transaction.get(rating)).thenReturn(invalid)
            failure<SecurityException> { repository.saveForStay("booking #1", 4) }
        }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun deniedReadsAreUnavailableButNetworkFailuresRemainRetryable() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(denied.code).thenReturn(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        `when`(stay.get()).thenReturn(Tasks.forException(denied))
        assertNull(repository.getForStay("booking #1"))
        val network = mock(FirebaseFirestoreException::class.java)
        `when`(network.code).thenReturn(FirebaseFirestoreException.Code.UNAVAILABLE)
        `when`(stay.get()).thenReturn(Tasks.forException(network))
        assertSame(network, failure<FirebaseFirestoreException> { repository.getForStay("booking #1") })
    }

    @Test fun transactionRetriesRecheckEligibilityAndReadsRejectAccountChanges() = runTest {
        val retried = mock(Transaction::class.java)
        val cancelled = booking(mapOf("status" to "cancelled"))
        `when`(retried.get(stay)).thenReturn(cancelled)
        attempts = listOf(transaction, retried)
        failure<IllegalStateException> { repository.saveForStay("booking #1", 4) }
        verify(retried, never()).set(any(DocumentReference::class.java), anyMap<String, Any>())
        val owned = booking()
        `when`(stay.get()).thenAnswer { `when`(auth.currentUser).thenReturn(null); Tasks.forResult(owned) }
        failure<SecurityException> { repository.getForStay("booking #1") }
    }

    /** Builds complete booking data in the shared reservation schema. */
    private fun booking(overrides: Map<String, Any?> = emptyMap()) = document(mapOf(
        "userId" to "alice", "ownerId" to "owner", "campsiteId" to "pine", "lotteryId" to "lottery", "offerId" to "offer",
        "startDate" to Timestamp(100, 0), "endDate" to Timestamp(200, 0), "status" to "confirmed", "cancellationRequested" to false,
        "guestCount" to 2L, "totalPrice" to 6550L, "rulesAccepted" to true) + overrides)

    /** Builds an existing rating with server timestamps, allowing malformed fields in individual tests. */
    private fun savedRating(overrides: Map<String, Any?> = emptyMap()) = document(mapOf(
        "campsiteId" to "pine", "userId" to "alice", "score" to 4L,
        "createdAt" to Timestamp(210, 0), "updatedAt" to Timestamp(220, 0)) + overrides)

    /** Supplies document fields without relying on Firebase's Android runtime. */
    private fun document(fields: Map<String, Any?> = emptyMap(), exists: Boolean = true): DocumentSnapshot =
        mock(DocumentSnapshot::class.java).also { document ->
            `when`(document.exists()).thenReturn(exists)
            `when`(document.id).thenReturn("booking #1")
            fields.forEach { (name, value) -> `when`(document.get(name)).thenReturn(value) }
        }

    /** Checks suspend failures without accidentally accepting a different exception type. */
    private suspend inline fun <reified T : Throwable> failure(action: () -> Unit): T {
        try { action() } catch (error: Throwable) { assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T); return error as T }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
