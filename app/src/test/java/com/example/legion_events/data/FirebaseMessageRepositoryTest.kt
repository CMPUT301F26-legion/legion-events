/*
 * FirebaseMessageRepositoryTest.kt
 *
 * Purpose: Verify participant ownership, two-way sending, exact chat fields, and uncertain retries.
 * Role: Local JVM tests at the Firebase SDK boundary; announcements are outside this repository.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import android.util.SparseArray
import com.example.legion_events.model.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.mockito.Mockito.*

/** Exercises the production chat implementation without contacting Firestore. */
class FirebaseMessageRepositoryTest {
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val sites = mock(CollectionReference::class.java)
    private val threads = mock(CollectionReference::class.java)
    private val messages = mock(CollectionReference::class.java)
    private val site = mock(DocumentReference::class.java)
    private val thread = mock(DocumentReference::class.java)
    private val message = mock(DocumentReference::class.java)
    private val batch = mock(WriteBatch::class.java)
    private val id = conversationKey("pine", "alice", "owner")
    private val repository = FirebaseMessageRepository(auth, firestore)

    @Before fun setUp() {
        `when`(auth.currentUser).thenReturn(user); `when`(user.uid).thenReturn("alice")
        `when`(firestore.collection("campsites")).thenReturn(sites); `when`(sites.document("pine")).thenReturn(site)
        `when`(firestore.collection("conversations")).thenReturn(threads); `when`(threads.document(id)).thenReturn(thread)
        `when`(firestore.collection("messages")).thenReturn(messages); `when`(messages.document("message")).thenReturn(message)
        val campsite = doc(mapOf("ownerId" to "owner", "active" to true, "name" to "Pine Ridge"), "pine")
        val conversation = conversation()
        `when`(site.get()).thenReturn(Tasks.forResult(campsite))
        `when`(thread.get()).thenReturn(Tasks.forResult(conversation))
        `when`(thread.get(Source.SERVER)).thenReturn(Tasks.forResult(conversation))
        `when`(thread.set(anyMap<String, Any?>())).thenReturn(Tasks.forResult<Void>(null))
        `when`(firestore.batch()).thenReturn(batch)
        `when`(batch.commit()).thenReturn(Tasks.forResult<Void>(null))
    }

    @Test fun startUsesOneStableKeyAndTheExactConversationContract() = runTest {
        assertEquals(id, repository.startConversation("pine"))
        verify(thread).set(mapOf("campsiteId" to "pine", "entrantId" to "alice", "ownerId" to "owner",
            "lotteryId" to null, "reservationId" to null, "lastMessage" to "",
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        assertEquals(64, id.length)
        assertNotEquals(id, conversationKey("pine", "bob", "owner"))
        assertNotEquals(conversationKey("a_b", "c", "owner"), conversationKey("a", "b_c", "owner"))
    }

    @Test fun anExistingConversationIsReopenedWithoutResettingItsPreview() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        `when`(thread.set(anyMap<String, Any?>())).thenReturn(Tasks.forException(denied))
        assertEquals(id, repository.startConversation("pine"))
        verify(thread).get(Source.SERVER)
        verify(batch, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun entrantAndOwnerCanSendButTheReceiverAlwaysComesFromMembership() = runTest {
        repository.send(id, "message", " Hello ")
        verify(batch).set(message, mapOf("conversationId" to id, "senderId" to "alice", "receiverId" to "owner",
            "text" to "Hello", "read" to false, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        verify(batch).update(thread, mapOf("lastMessage" to "Hello", "updatedAt" to FieldValue.serverTimestamp()))
        `when`(user.uid).thenReturn("owner")
        repository.send(id, "message", "Reply")
        verify(batch).set(message, mapOf("conversationId" to id, "senderId" to "owner", "receiverId" to "alice",
            "text" to "Reply", "read" to false, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        verify(firestore, never()).collection("ownerNotices")
        verify(site, never()).get()
        verify(thread, times(2)).get(Source.SERVER)
    }

    @Test fun uncertainSendVerifiesTheSameImmutableMessageInsteadOfCreatingAnother() = runTest {
        val denied = mock(FirebaseFirestoreException::class.java)
        val saved = sent()
        `when`(batch.commit()).thenReturn(Tasks.forException(denied))
        `when`(message.get(Source.SERVER)).thenReturn(Tasks.forResult(saved))
        repository.send(id, "message", "Hello")
        verify(message).get(Source.SERVER)
        fails { repository.send(id, "message", "Different text") }
    }

    @Test fun strangersOwnersStartingSelfChatsAndInvalidTextCannotWrite() = runTest {
        `when`(user.uid).thenReturn("stranger")
        fails { repository.send(id, "message", "Hello") }
        `when`(user.uid).thenReturn("owner")
        fails { repository.startConversation("pine") }
        verify(batch, never()).commit()
        clearInvocations(firestore)
        for (text in listOf(" ", "x".repeat(MAX_CHAT_LENGTH + 1))) fails { repository.send(id, "message", text) }
        fails { repository.send("nested/path", "message", "Hello") }
        `when`(auth.currentUser).thenReturn(null)
        fails { repository.send(id, "message", "Hello") }
        verifyNoInteractions(firestore)
    }

    @Test fun messagesMustBelongToTheSelectedThreadAndItsTwoParticipants() {
        val conversation = conversation().toConversation("alice")
        assertEquals("owner", conversation.receiverFor("alice"))
        assertEquals("alice", conversation.receiverFor("owner"))
        for (fields in listOf(mapOf("senderId" to "stranger"), mapOf("receiverId" to "stranger"), mapOf("conversationId" to "another"))) {
            assertThrows(Exception::class.java) { sent(fields).toChatMessage(conversation) }
        }
        assertThrows(Exception::class.java) { conversation().toConversation("stranger") }
    }

    @Test fun missingOrDeniedThreadsAreUnavailableAndAccountChangesRejectLateResults() = runTest {
        val denied = firestoreError("PERMISSION_DENIED")
        `when`(thread.get()).thenReturn(Tasks.forException(denied))
        assertNull(repository.getConversation(id))
        val owned = conversation()
        `when`(thread.get()).thenAnswer { `when`(auth.currentUser).thenReturn(null); Tasks.forResult(owned) }
        fails { repository.getConversation(id) }
    }

    @Test fun pastContactsComeFromBothMembershipQueriesAndOnlyForTheSignedInUser() = runTest {
        val asEntrant = mock(Query::class.java); val asOwner = mock(Query::class.java)
        `when`(threads.whereEqualTo("entrantId", "alice")).thenReturn(asEntrant)
        `when`(threads.whereEqualTo("ownerId", "alice")).thenReturn(asOwner)
        val empty = doc(mapOf("campsiteId" to "pine", "entrantId" to "alice", "ownerId" to "quiet", "lastMessage" to "",
            "createdAt" to Timestamp(1, 0), "updatedAt" to Timestamp(9, 0)), "empty")
        val hosted = doc(mapOf("campsiteId" to "lake", "entrantId" to "bob", "ownerId" to "alice", "lastMessage" to "Hi",
            "createdAt" to Timestamp(1, 0), "updatedAt" to Timestamp(5, 0)), "hosted")
        val entrantThreads = snapshot(conversation(), empty); val ownerThreads = snapshot(hosted)
        `when`(asEntrant.get()).thenReturn(Tasks.forResult(entrantThreads))
        `when`(asOwner.get()).thenReturn(Tasks.forResult(ownerThreads))
        assertEquals(listOf("bob", "owner"), repository.getPastContacts("alice"))
        clearInvocations(firestore)
        assertThrows(SecurityException::class.java) { kotlinx.coroutines.runBlocking { repository.getPastContacts("bob") } }
        verifyNoInteractions(firestore)
    }

    @Test fun pastContactsSkipEmptyAndForeignThreadsAndListEachPersonOnce() {
        fun c(id: String, entrant: String, owner: String, last: String, at: Long) =
            Conversation(id, "site", entrant, owner, last, Timestamp(1, 0), Timestamp(at, 0))
        val threads = listOf(c("a", "alice", "owner", "Hi", 2), c("b", "alice", "owner", "Again", 4),
            c("c", "bob", "alice", "Yo", 3), c("d", "alice", "quiet", "", 9), c("e", "bob", "carol", "Not mine", 10))
        assertEquals(listOf("owner", "bob"), pastContacts(threads, "alice"))
        assertEquals(emptyList<String>(), pastContacts(threads, "nobody"))
    }

    /** Wraps document mocks in a query result. */
    private fun snapshot(vararg documents: DocumentSnapshot) = mock(QuerySnapshot::class.java).also {
        `when`(it.documents).thenReturn(documents.toList())
    }

    /** Builds stored conversation membership with an existing preview. */
    private fun conversation() = doc(mapOf("campsiteId" to "pine", "entrantId" to "alice", "ownerId" to "owner",
        "lastMessage" to "Previous message", "createdAt" to Timestamp(1, 0), "updatedAt" to Timestamp(2, 0)), id)
    /** Builds a committed message; tests replace membership fields to simulate invalid data. */
    private fun sent(extra: Map<String, Any?> = emptyMap()) = doc(mapOf("conversationId" to id, "senderId" to "alice",
        "receiverId" to "owner", "text" to "Hello", "read" to false, "createdAt" to Timestamp(3, 0)) + extra, "message")
    /** Supplies synthetic document fields to the real mapper. */
    private fun doc(fields: Map<String, Any?>, id: String) = mock(DocumentSnapshot::class.java).also { document ->
        `when`(document.exists()).thenReturn(true); `when`(document.id).thenReturn(id)
        fields.forEach { (key, value) -> `when`(document.get(key)).thenReturn(value) }
    }
    /** Asserts that an unauthorized or invalid operation fails rather than silently succeeding. */
    private suspend fun fails(action: suspend () -> Unit) { try { action() } catch (_: Exception) { return }; fail("Expected failure") }

    /** Initializes real SDK error codes using the same local Android-map shim as the offer tests. */
    private fun firestoreError(code: String): FirebaseFirestoreException = mockConstruction(SparseArray::class.java) { array, _ ->
        val entries = mutableMapOf<Int, Any?>()
        doAnswer { entries[it.getArgument<Int>(0)] }.`when`(array).get(anyInt())
        @Suppress("UNCHECKED_CAST") val values = array as SparseArray<Any?>
        doAnswer { entries[it.getArgument<Int>(0)] = it.getArgument<Any?>(1); null }.`when`(values).put(anyInt(), any())
    }.use {
        val sdkCode = FirebaseFirestoreException.Code.valueOf(code)
        mock(FirebaseFirestoreException::class.java).also { `when`(it.code).thenReturn(sdkCode) }
    }
}
