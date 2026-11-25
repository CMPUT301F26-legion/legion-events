/* Tests the Android draw transaction at Firebase SDK boundaries with synthetic snapshots. */
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

/** Checks fresh deadline and entrant validation and the atomic write set. */
class FirebaseLocalLotteryDrawRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val transaction = mock(Transaction::class.java)
    private val query = mock(Query::class.java)
    private val references = mutableMapOf<String, DocumentReference>()
    private val snapshots = mutableMapOf<String, DocumentSnapshot>()
    private val writes = mutableMapOf<String, Map<String, Any?>>()
    private val clock = Timestamp(100, 0)
    private lateinit var repository: FirebaseLocalLotteryDrawRepository
    private lateinit var initial: DocumentSnapshot
    private lateinit var current: DocumentSnapshot
    private var entrants = 1L
    private var drawn = false
    private var end = Timestamp(99, 0)

    /** Makes SDK calls synchronous while retaining the real repository transaction callback. */
    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("owner")
        `when`(firestore.collection(anyString())).thenAnswer { call ->
            val collectionName = call.getArgument<String>(0)
            mock(CollectionReference::class.java).also { collection ->
                `when`(collection.document(anyString())).thenAnswer { document ->
                    reference("$collectionName/${document.getArgument<String>(0)}")
                }
                `when`(collection.whereEqualTo("lotteryId", "pine")).thenReturn(query)
            }
        }
        `when`(query.whereEqualTo("status", "joined")).thenReturn(query)
        `when`(query.limit(101L)).thenReturn(query)
        val result = mock(QuerySnapshot::class.java)
        `when`(query.get(Source.SERVER)).thenReturn(Tasks.forResult(result))
        val entry = document("waitlist/pine_alice", mapOf("lotteryId" to "pine", "userId" to "alice", "status" to "joined"))
        `when`(result.documents).thenReturn(listOf(entry))
        initial = document("lotteries/pine", lotteryFields())
        current = document("lotteries/pine", lotteryFields())
        `when`(current.getLong("entrantCount")).thenAnswer { entrants }
        `when`(current.getBoolean("winnersDrawn")).thenAnswer { drawn }
        `when`(current.getTimestamp("registrationEndAt")).thenAnswer { end }
        `when`(reference("lotteries/pine").get(Source.SERVER)).thenReturn(Tasks.forResult(initial))
        val missing = mock(DocumentSnapshot::class.java)
        `when`(transaction.get(any(DocumentReference::class.java))).thenAnswer { call ->
            val path = call.getArgument<DocumentReference>(0).path
            if (path == "lotteries/pine") current else snapshots[path] ?: missing
        }
        `when`(transaction.set(any(DocumentReference::class.java), anyMap<String, Any?>())).thenAnswer { call ->
            writes[call.getArgument<DocumentReference>(0).path] = call.getArgument(1)
            transaction
        }
        `when`(transaction.update(any(DocumentReference::class.java), anyMap<String, Any?>())).thenAnswer { call ->
            writes[call.getArgument<DocumentReference>(0).path] = call.getArgument(1)
            transaction
        }
        `when`(firestore.runTransaction<Boolean>(any())).thenAnswer { call ->
            try { Tasks.forResult(call.getArgument<Transaction.Function<Boolean>>(0).apply(transaction)) }
            catch (failure: Exception) { Tasks.forException<Boolean>(failure) }
        }
        repository = FirebaseLocalLotteryDrawRepository(auth, firestore) { clock }
    }

    /** A completed draw writes the result, offer, manifest, Inbox, and push event together. */
    @Test fun savesWinningOfferAndInboxWithTheDrawFlag() = runTest {
        assertTrue(repository.draw("pine"))
        assertEquals(setOf("lotteryDraws/pine", "waitlist/pine_alice", "offers/pine_alice",
            "notifications/pine_alice", "lotteryNotificationOutbox/pine_alice", "lotteries/pine"), writes.keys)
        assertEquals(listOf("alice"), writes.getValue("lotteryDraws/pine")["winnerIds"])
        assertEquals("pending", writes.getValue("offers/pine_alice")["status"])
        assertEquals("lottery_won", writes.getValue("notifications/pine_alice")["type"])
        assertEquals("pine_alice", writes.getValue("notifications/pine_alice")["offerId"])
        assertEquals(true, writes.getValue("lotteries/pine")["winnersDrawn"])
    }

    /** A deadline extension between the initial query and transaction prevents an early draw. */
    @Test fun transactionRechecksAnExtendedDeadline() = runTest {
        end = Timestamp(101, 0)
        assertFalse(repository.draw("pine"))
        assertTrue(writes.isEmpty())
    }

    /** A cloud draw committed while the Android query ran causes the client attempt to skip. */
    @Test fun alreadyDrawnByCloudWorkerIsSkipped() = runTest {
        drawn = true
        assertFalse(repository.draw("pine"))
        assertTrue(writes.isEmpty())
    }

    /** A stale entrant query fails before saving a partial selection. */
    @Test fun entrantCountMismatchRequiresAFreshQuery() = runTest {
        entrants = 2
        try { repository.draw("pine"); fail("Expected an incomplete-list failure") }
        catch (_: IllegalStateException) { assertTrue(writes.isEmpty()) }
    }

    /** Another authenticated user cannot use this repository to draw an owner's lottery. */
    @Test fun anotherOwnerCannotDrawTheLottery() = runTest {
        `when`(user.uid).thenReturn("other")
        try { repository.draw("pine"); fail("Expected an owner check") }
        catch (_: IllegalStateException) { verifyNoInteractions(transaction) }
    }

    /** Resolves stable document references so writes can be checked by their real paths. */
    private fun reference(path: String): DocumentReference = references.getOrPut(path) {
        mock(DocumentReference::class.java).also { `when`(it.path).thenReturn(path) }
    }

    /** Creates a complete Firebase snapshot with typed field access used by the mapper. */
    private fun document(path: String, fields: Map<String, Any?>): DocumentSnapshot {
        val documentReference = reference(path)
        return mock(DocumentSnapshot::class.java).also { snapshot ->
            `when`(snapshot.exists()).thenReturn(true)
            `when`(snapshot.id).thenReturn(path.substringAfterLast('/'))
            `when`(snapshot.reference).thenReturn(documentReference)
            `when`(snapshot.get(anyString())).thenAnswer { fields[it.getArgument<String>(0)] }
            `when`(snapshot.getString(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as String? }
            `when`(snapshot.getBoolean(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as Boolean? }
            `when`(snapshot.getLong(anyString())).thenAnswer { (fields[it.getArgument<String>(0)] as Number?)?.toLong() }
            `when`(snapshot.getTimestamp(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as Timestamp? }
            snapshots[path] = snapshot
        }
    }

    /** Supplies the same required lottery fields the Android app writes. */
    private fun lotteryFields(): Map<String, Any?> = mapOf(
        "ownerId" to "owner", "campsiteIds" to listOf("camp"), "registrationStartAt" to Timestamp(1, 0),
        "registrationEndAt" to Timestamp(99, 0), "bookingStartAt" to Timestamp(100, 0), "bookingEndAt" to Timestamp(300, 0),
        "winnerCount" to 1L, "waitlistLimit" to 100L, "entrantCount" to 1L, "status" to "open", "winnersDrawn" to false,
    )
}
