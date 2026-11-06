/* Tests offer access and decline decisions without connecting to a Firebase project. */
package com.example.legion_events.data

import android.util.SparseArray
import com.example.legion_events.model.BookingOffer
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
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/** Exercises the real repository with mocked SDK boundaries and a fixed deadline clock. */
class FirebaseOfferRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val collection = mock(CollectionReference::class.java)
    private val reference = mock(DocumentReference::class.java)
    private val query = mock(Query::class.java)
    private val transaction = mock(Transaction::class.java)
    private val currentTime = Timestamp(1_800_000_000, 0)
    private val createdAt = Timestamp(1_700_000_000, 0)
    private val savedAt = Timestamp(1_800_000_001, 0)
    private val deadline = Timestamp(1_800_000_010, 0)
    private var attempts = listOf(transaction)
    private val repository = FirebaseOfferRepository(auth, firestore) { currentTime }

    /** Provides immediate tasks while running the SDK transaction callback against test snapshots. */
    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("entrant")
        `when`(firestore.collection("offers")).thenReturn(collection)
        `when`(collection.document("offer")).thenReturn(reference)
        `when`(collection.whereEqualTo("userId", "entrant")).thenReturn(query)
        doAnswer { invocation ->
            val function = invocation.getArgument<Transaction.Function<Unit>>(0)
            try {
                attempts.forEach { function.apply(it) }
                Tasks.forResult(Unit)
            } catch (error: Exception) {
                Tasks.forException<Unit>(error)
            }
        }.`when`(firestore).runTransaction<Unit>(any())
    }

    @Test fun signedOutCallsFailBeforeDatabaseAccess() = runTest {
        `when`(auth.currentUser).thenReturn(null)
        clearInvocations(firestore)
        expectFailure(IllegalStateException::class.java) { repository.getOffer("offer") }
        expectFailure(IllegalStateException::class.java) { repository.getOffersForCurrentUser() }
        expectFailure(IllegalStateException::class.java) { repository.declineOffer("offer") }
        verifyNoInteractions(firestore)
    }

    @Test fun readsTheDocumentIdAndSavedFieldsWithoutInventingOptionalTimestamps() = runTest {
        val document = offer()
        `when`(reference.get()).thenReturn(Tasks.forResult(document))
        assertEquals(
            BookingOffer("offer", "lottery", "entrant", expiresAt = deadline, createdAt = createdAt),
            repository.getOffer("offer"),
        )
    }

    @Test fun aMissingOfferReturnsNullBeforeReadingItsFields() = runTest {
        val missing = mock(DocumentSnapshot::class.java)
        `when`(reference.get()).thenReturn(Tasks.forResult(missing))
        assertNull(repository.getOffer("offer"))
        verify(missing, never()).get(anyString())
    }

    @Test fun anotherUsersOfferCannotBeRead() = runTest {
        val document = offer(ownerId = "other")
        `when`(reference.get()).thenReturn(Tasks.forResult(document))
        expectFailure(SecurityException::class.java) { repository.getOffer("offer") }
    }

    @Test fun malformedOffersAreRejectedInsteadOfDefaultingToOpen() = runTest {
        for (document in listOf(
            offer(ownerId = null), offer(ownerId = " "), offer(lotteryId = null),
            offer(lotteryId = ""), offer(status = null), offer(status = "unknown"),
            offer(response = "unknown"),
        )) {
            `when`(reference.get()).thenReturn(Tasks.forResult(document))
            expectFailure(IllegalStateException::class.java) { repository.getOffer("offer") }
        }
    }

    @Test fun fieldsStoredWithTheWrongTypesAreRejected() = runTest {
        for ((field, value) in listOf("lotteryId" to 42L, "expiresAt" to "tomorrow")) {
            val document = offer()
            `when`(document.get(field)).thenReturn(value)
            `when`(reference.get()).thenReturn(Tasks.forResult(document))
            expectFailure(IllegalStateException::class.java) { repository.getOffer("offer") }
        }
    }

    @Test fun blankIdsAndNestedDocumentPathsAreRejected() = runTest {
        clearInvocations(collection)
        for (id in listOf("", " ", "offer/nested/document")) {
            expectFailure(IllegalArgumentException::class.java) { repository.getOffer(id) }
            expectFailure(IllegalArgumentException::class.java) { repository.declineOffer(id) }
        }
        verify(collection, never()).document(anyString())
    }

    @Test fun listingQueriesTheAuthenticatedUidAndIncludesAllStatuses() = runTest {
        val statuses = listOf("open", "used", "declined", "expired")
        val result = mock(QuerySnapshot::class.java)
        val documents = listOf(
            offer(id = "open"),
            offer(id = "used", status = "used"),
            offer(id = "declined", response = "decline"),
            offer(id = "expired", status = "expired"),
        )
        `when`(result.documents).thenReturn(documents)
        `when`(query.get()).thenReturn(Tasks.forResult(result))
        val offers = repository.getOffersForCurrentUser()
        assertEquals(statuses, offers.map { it.status })
        assertTrue(offers.all { it.userId == "entrant" })
        verify(collection).whereEqualTo("userId", "entrant")
        verify(collection, never()).document(anyString())
    }

    @Test fun listingWithoutMatchesReturnsAnEmptyList() = runTest {
        val result = mock(QuerySnapshot::class.java)
        `when`(result.documents).thenReturn(emptyList())
        `when`(query.get()).thenReturn(Tasks.forResult(result))
        assertTrue(repository.getOffersForCurrentUser().isEmpty())
    }

    @Test fun listingAlsoValidatesTheStoredOwner() = runTest {
        val result = mock(QuerySnapshot::class.java)
        val document = offer(ownerId = "other")
        `when`(result.documents).thenReturn(listOf(document))
        `when`(query.get()).thenReturn(Tasks.forResult(result))
        expectFailure(SecurityException::class.java) { repository.getOffersForCurrentUser() }
    }

    @Test fun permissionDenialsBecomeAccessErrorsAndRetainTheirCause() = runTest {
        val denied = firestoreError("PERMISSION_DENIED")
        `when`(reference.get()).thenReturn(Tasks.forException(denied))
        val error = expectFailure(SecurityException::class.java) { repository.getOffer("offer") }
        assertSame(denied, error.cause)
    }

    @Test fun networkFailuresPropagateForTheCallerToRetry() = runTest {
        val unavailable = firestoreError("UNAVAILABLE")
        `when`(reference.get()).thenReturn(Tasks.forException(unavailable))
        assertSame(
            unavailable,
            expectFailure(FirebaseFirestoreException::class.java) { repository.getOffer("offer") },
        )
    }

    @Test fun declineWritesOnlyTheResponseAndServerTimes() = runTest {
        val open = offer()
        val saved = offer(response = "decline", updatedAt = savedAt)
        `when`(transaction.get(reference)).thenReturn(open)
        `when`(reference.get(Source.SERVER)).thenReturn(
            Tasks.forResult(saved),
        )
        val declined = repository.declineOffer("offer")
        assertEquals("declined", declined.status)
        assertEquals(savedAt, declined.updatedAt)
        assertEquals(createdAt, declined.createdAt)
        assertEquals("lottery", declined.lotteryId)
        verify(transaction).update(
            reference,
            mapOf(
                "response" to "decline",
                "respondedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        verify(reference).get(Source.SERVER)
        verify(reference, never()).get()
    }

    @Test fun aFutureResponseDeadlineAllowsDeclining() = runTest {
        val deadline = Timestamp(currentTime.seconds, 1)
        val open = offer(expiresAt = deadline)
        val saved = offer(response = "decline", expiresAt = deadline, updatedAt = savedAt)
        `when`(transaction.get(reference)).thenReturn(open)
        `when`(reference.get(Source.SERVER)).thenReturn(
            Tasks.forResult(saved),
        )
        assertEquals("declined", repository.declineOffer("offer").status)
    }

    @Test fun usedExpiredUnknownAndPastDeadlineOffersCannotBeDeclined() = runTest {
        for (document in listOf(
            offer(status = "used"), offer(status = "expired"), offer(status = "unknown"),
            offer(expiresAt = currentTime), offer(expiresAt = createdAt), offer(expiresAt = null),
            offer(response = "unknown"), offer(lotteryId = null),
        )) {
            `when`(transaction.get(reference)).thenReturn(document)
            expectFailure(IllegalStateException::class.java) { repository.declineOffer("offer") }
        }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(reference, never()).get(Source.SERVER)
    }

    @Test fun alreadyDeclinedOffersKeepTheirTimestampEvenAfterTheDeadline() = runTest {
        val declined = offer(response = "decline", expiresAt = createdAt, updatedAt = savedAt)
        `when`(transaction.get(reference)).thenReturn(declined)
        `when`(reference.get(Source.SERVER)).thenReturn(Tasks.forResult(declined))
        repeat(2) { assertEquals(savedAt, repository.declineOffer("offer").updatedAt) }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun anotherUsersAlreadyDeclinedOfferStillFailsOwnership() = runTest {
        val document = offer(ownerId = "other", response = "decline")
        `when`(transaction.get(reference)).thenReturn(document)
        expectFailure(SecurityException::class.java) { repository.declineOffer("offer") }
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun aMissingOfferCannotBeDeclined() = runTest {
        val missing = mock(DocumentSnapshot::class.java)
        `when`(transaction.get(reference)).thenReturn(missing)
        expectFailure(IllegalStateException::class.java) { repository.declineOffer("offer") }
        verify(missing, never()).get(anyString())
        verify(transaction, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun aTransactionRetryRechecksTheStatusBeforeAnotherWrite() = runTest {
        val retried = mock(Transaction::class.java)
        attempts = listOf(transaction, retried)
        val open = offer()
        val used = offer(status = "used")
        `when`(transaction.get(reference)).thenReturn(open)
        `when`(retried.get(reference)).thenReturn(used)
        expectFailure(IllegalStateException::class.java) { repository.declineOffer("offer") }
        verify(retried, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
        verify(reference, never()).get(Source.SERVER)
    }

    @Test fun aLostReadAfterCommitCanBeRetriedWithoutWritingAgain() = runTest {
        val open = offer()
        val declined = offer(response = "decline", updatedAt = savedAt)
        val unavailable = firestoreError("UNAVAILABLE")
        `when`(transaction.get(reference)).thenReturn(open, declined)
        `when`(reference.get(Source.SERVER)).thenReturn(
            Tasks.forException(unavailable), Tasks.forResult(declined),
        )
        expectFailure(FirebaseFirestoreException::class.java) { repository.declineOffer("offer") }
        assertEquals(savedAt, repository.declineOffer("offer").updatedAt)
        verify(transaction, times(1)).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    /** Supplies stored fields without using Firestore object mapping or a live database. */
    private fun offer(
        id: String = "offer",
        ownerId: String? = "entrant",
        lotteryId: String? = "lottery",
        status: String? = "pending",
        response: String? = null,
        expiresAt: Timestamp? = deadline,
        updatedAt: Timestamp? = null,
    ): DocumentSnapshot = mock(DocumentSnapshot::class.java).also { document ->
        `when`(document.exists()).thenReturn(true)
        `when`(document.id).thenReturn(id)
        `when`(document.get("userId")).thenReturn(ownerId)
        `when`(document.get("lotteryId")).thenReturn(lotteryId)
        `when`(document.get("status")).thenReturn(status)
        `when`(document.get("response")).thenReturn(response)
        `when`(document.get("expiresAt")).thenReturn(expiresAt)
        `when`(document.get("createdAt")).thenReturn(createdAt)
        `when`(document.get("updatedAt")).thenReturn(updatedAt)
    }

    /** Checks failures from suspend methods while preserving unexpected errors for diagnosis. */
    private suspend fun <T : Throwable> expectFailure(type: Class<T>, action: suspend () -> Unit): T {
        try {
            action()
        } catch (error: Throwable) {
            if (!type.isInstance(error)) throw error
            return checkNotNull(type.cast(error))
        }
        throw AssertionError("Expected ${type.simpleName}")
    }

    /** Supplies the Android map used to initialize real SDK error codes in a local JVM test. */
    private fun firestoreError(code: String): FirebaseFirestoreException =
        mockConstruction(SparseArray::class.java) { array, _ ->
            val entries = mutableMapOf<Int, Any?>()
            doAnswer { entries[it.getArgument<Int>(0)] }.`when`(array).get(anyInt())
            @Suppress("UNCHECKED_CAST")
            val values = array as SparseArray<Any?>
            doAnswer {
                entries[it.getArgument<Int>(0)] = it.getArgument<Any?>(1)
                null
            }.`when`(values).put(anyInt(), any())
        }.use {
            val sdkCode = FirebaseFirestoreException.Code.valueOf(code)
            mock(FirebaseFirestoreException::class.java).also { error ->
                `when`(error.code).thenReturn(sdkCode)
            }
        }
}
