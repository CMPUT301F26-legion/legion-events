/*
 * FirebaseMessageRepository.kt
 *
 * Purpose: Store and observe private chat using the existing conversations/messages schema.
 * Role: Data layer in MVC; enforces participant access and idempotent creates and sends.
 * Outstanding issues: Paging and chat push notifications are separate work.
 */
package com.example.legion_events.data

import com.example.legion_events.model.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Uses membership queries; announcements and owner notices are never changed by chat.
 * @property auth Supplies the authenticated participant.
 * @property firestore Supplies the app's existing Firestore database.
 */
class FirebaseMessageRepository(private val auth: FirebaseAuth, private val firestore: FirebaseFirestore) : MessageRepository {
    /** Combines entrant and owner queries so either participant can discover and reopen a thread. */
    override fun observeConversations(): Flow<List<ConversationSummary>> {
        val uid = userId()
        val names = mutableMapOf<String, String?>()
        return combine(threads("entrantId", uid), threads("ownerId", uid)) { entrant, owner ->
            (entrant + owner).distinctBy { it.id }.sortedWith(compareByDescending<Conversation> { it.updatedAt }.thenBy { it.id })
        }.map { conversations ->
            val summaries = conversations.map { conversation ->
                if (!names.containsKey(conversation.campsiteId)) names[conversation.campsiteId] = campsiteName(conversation.campsiteId)
                ConversationSummary(conversation, names[conversation.campsiteId], uid)
            }
            checkIdentity(uid)
            summaries
        }
    }

    /** Registers one UID-scoped membership listener and removes it when collection stops. */
    private fun threads(field: String, uid: String): Flow<List<Conversation>> = callbackFlow {
        checkIdentity(uid)
        val listener = firestore.collection("conversations").whereEqualTo(field, uid).addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            try {
                checkIdentity(uid)
                trySend(snapshot?.documents.orEmpty().map { it.toConversation(uid) })
            } catch (failure: Exception) { close(failure) }
        }
        awaitClose { listener.remove() }
    }

    /** A deterministic key avoids duplicate threads without granting reads of missing private documents. */
    override suspend fun startConversation(campsiteId: String): String {
        validId(campsiteId)
        val uid = userId()
        val site = firestore.collection("campsites").document(campsiteId).get().await()
        checkIdentity(uid)
        check(site.exists() && site.get("active") == true) { "This campsite is unavailable." }
        val owner = checkNotNull(site.get("ownerId") as? String)
        validId(owner)
        check(owner != uid) { "You own this campsite." }
        val id = conversationKey(campsiteId, uid, owner)
        val reference = firestore.collection("conversations").document(id)
        try {
            reference.set(mapOf("campsiteId" to campsiteId, "entrantId" to uid, "ownerId" to owner,
                "lotteryId" to null, "reservationId" to null, "lastMessage" to "",
                "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp())).await()
        } catch (error: FirebaseFirestoreException) {
            // Existing threads are create-only here: rules reject resetting their creation time or preview.
            val existing = reference.get(Source.SERVER).await()
            checkIdentity(uid)
            if (!existing.exists()) throw error
            val conversation = existing.toConversation(uid)
            check(conversation.campsiteId == campsiteId && conversation.entrantId == uid && conversation.ownerId == owner)
        }
        checkIdentity(uid)
        return id
    }

    /** Checks membership before returning details; missing-document rule denials are unavailable too. */
    override suspend fun getConversation(conversationId: String): ConversationSummary? {
        validId(conversationId)
        val uid = userId()
        try {
            val document = firestore.collection("conversations").document(conversationId).get().await()
            checkIdentity(uid)
            if (!document.exists()) return null
            val conversation = document.toConversation(uid)
            val name = campsiteName(conversation.campsiteId)
            checkIdentity(uid)
            return ConversationSummary(conversation, name, uid)
        } catch (error: FirebaseFirestoreException) {
            checkIdentity(uid)
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return null
            throw error
        }
    }

    /** Verifies membership before attaching a thread-scoped message listener, including for an empty thread. */
    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = callbackFlow {
        val uid = userId()
        val summary = checkNotNull(getConversation(conversationId)) { "This conversation is unavailable." }
        checkIdentity(uid)
        val listener = firestore.collection("messages").whereEqualTo("conversationId", conversationId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                try {
                    checkIdentity(uid)
                    val messages = snapshot?.documents.orEmpty().map { it.toChatMessage(summary.conversation) }
                        .sortedWith(compareBy<ChatMessage> { it.createdAt == null }.thenBy { it.createdAt }.thenBy { it.id })
                    trySend(messages)
                } catch (failure: Exception) { close(failure) }
            }
        awaitClose { listener.remove() }
    }

    /** Creates an immutable message and preview atomically; a retry verifies the existing message instead of duplicating it. */
    override suspend fun send(conversationId: String, messageId: String, text: String) {
        validId(conversationId); validId(messageId)
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= MAX_CHAT_LENGTH) { "Write 1 to $MAX_CHAT_LENGTH characters." }
        val uid = userId()
        // Send needs fresh membership, not the optional campsite label used by the screens.
        val document = firestore.collection("conversations").document(conversationId).get(Source.SERVER).await()
        checkIdentity(uid)
        check(document.exists()) { "This conversation is unavailable." }
        val conversation = document.toConversation(uid)
        val receiver = conversation.receiverFor(uid)
        val reference = firestore.collection("messages").document(messageId)
        val batch = firestore.batch()
        batch.set(reference, mapOf("conversationId" to conversationId, "senderId" to uid, "receiverId" to receiver,
            "text" to body, "read" to false, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        batch.update(firestore.collection("conversations").document(conversationId),
            mapOf("lastMessage" to body, "updatedAt" to FieldValue.serverTimestamp()))
        try { batch.commit().await() }
        catch (error: FirebaseFirestoreException) {
            val saved = reference.get(Source.SERVER).await()
            checkIdentity(uid)
            if (!saved.exists()) throw error
            val message = saved.toChatMessage(conversation)
            check(message.senderId == uid && message.text == body && message.receiverId == receiver) { "This message ID is already used." }
        }
        checkIdentity(uid)
    }

    /** Listing access is optional for an existing private thread, so removed campsites retain their chat. */
    private suspend fun campsiteName(id: String): String? = try {
        firestore.collection("campsites").document(id).get().await().get("name") as? String
    } catch (_: FirebaseFirestoreException) { null }

    /** Returns the current UID; callers never supply a different participant identity. */
    private fun userId(): String = checkNotNull(auth.currentUser) { "No user is signed in." }.uid
    /** Rejects late results after an account change. */
    private fun checkIdentity(uid: String) { check(auth.currentUser?.uid == uid) { "The signed-in account changed." } }

    companion object {
        /** Chat service using the app's existing Firebase configuration. */
        val instance by lazy { FirebaseMessageRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()) }
    }
}

/** Creates an unambiguous bounded key for one campsite and participant pair. */
internal fun conversationKey(campsiteId: String, entrantId: String, ownerId: String): String =
    MessageDigest.getInstance("SHA-256").digest("$campsiteId/$entrantId/$ownerId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Rejects paths and blank IDs before database access. */
private fun validId(id: String) { require(id.isNotBlank() && '/' !in id) { "A single document ID is required." } }

/** Maps membership only after checking that this reader is one of the two distinct participants. */
internal fun DocumentSnapshot.toConversation(uid: String): Conversation {
    val entrant = chatString("entrantId")
    val owner = chatString("ownerId")
    check(entrant != owner && uid in listOf(entrant, owner)) { "This conversation is private." }
    return Conversation(id, chatString("campsiteId"), entrant, owner, get("lastMessage") as? String ?: "",
        chatTime("createdAt"), chatTime("updatedAt"), get("lotteryId") as? String, get("reservationId") as? String)
}

/** Verifies thread and sender/receiver membership before displaying or acknowledging any message. */
internal fun DocumentSnapshot.toChatMessage(conversation: Conversation): ChatMessage {
    val sender = chatString("senderId")
    val receiver = chatString("receiverId")
    check(get("conversationId") == conversation.id && conversation.receiverFor(sender) == receiver) { "Invalid message membership." }
    val text = checkNotNull(get("text") as? String)
    check(text.isNotEmpty() && text.length <= MAX_CHAT_LENGTH) { "Invalid message text." }
    return ChatMessage(id, conversation.id, sender, receiver, text, get("read") as? Boolean ?: false,
        chatTime("createdAt"), chatTime("updatedAt"))
}

/** Reads a required document reference without allowing embedded paths. */
private fun DocumentSnapshot.chatString(field: String): String = checkNotNull(get(field) as? String).also(::validId)
/** Allows pending server timestamps and rejects other stored types. */
private fun DocumentSnapshot.chatTime(field: String): Timestamp? {
    val value = get(field)
    check(value == null || value is Timestamp) { "Invalid message time." }
    return value
}
