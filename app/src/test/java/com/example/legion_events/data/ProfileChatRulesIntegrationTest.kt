/*
 * ProfileChatRulesIntegrationTest.kt
 *
 * Purpose: Verify real Firestore rules for private chat queries and atomic profile/photo deletion.
 * Role: Opt-in local emulator integration tests using REST requests, not mocked Firebase access.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Loads the checked-in rules into a loopback emulator; production projects and hosts are rejected. */
class ProfileChatRulesIntegrationTest {
    private lateinit var origin: String
    private lateinit var project: String
    private lateinit var root: String
    private lateinit var prefix: String
    private val tag = UUID.randomUUID().toString()
    private val alice = "alice-$tag"
    private val owner = "owner-$tag"
    private val outsider = "outsider-$tag"
    private val site = "site-$tag"
    private val thread = "thread-$tag"
    private val otherThread = "other-thread-$tag"
    private val photo = "photo-$tag"

    /** Seeds isolated records after explicitly loading this repository's rules into a demo project. */
    @Before fun connect() {
        val host = System.getenv("CHAT_RULES_EMULATOR_HOST").orEmpty()
        assumeTrue("Set CHAT_RULES_EMULATOR_HOST to run local Firestore rules tests.", host.isNotBlank())
        require(host.matches(Regex("(127\\.0\\.0\\.1|localhost):[0-9]+"))) { "Only a loopback emulator is supported." }
        project = System.getenv("CHAT_RULES_PROJECT_ID") ?: "demo-legion-chat-review"
        require(project.startsWith("demo-")) { "Use a demo project for rules tests." }
        origin = "http://$host"
        root = "$origin/v1/projects/$project/databases/(default)/documents"
        prefix = "projects/$project/databases/(default)/documents/"
        val rules = listOf(File("firestore.rules"), File("../firestore.rules")).first { it.isFile }.readText()
        expect(200, request("PUT", "$origin/emulator/v1/projects/$project:securityRules",
            mapOf("rules" to mapOf("files" to listOf(mapOf("name" to "firestore.rules", "content" to rules))))))
        for (uid in listOf(alice, owner, outsider)) seed("users/$uid", mapOf("name" to "", "email" to "", "phone" to "",
            "role" to "user", "disabled" to false, "photoId" to if (uid == alice) photo else null,
            "notificationsEnabled" to true, "fcmToken" to null))
        seed("campsites/$site", mapOf("ownerId" to owner, "active" to true, "name" to "Test campsite"))
        seed("photos/$photo", mapOf("ownerId" to alice, "targetType" to "user", "targetId" to alice,
            "imageBase64" to "amVwZw==", "mimeType" to "image/jpeg"))
        commit(alice, listOf(update("conversations/$thread", conversation(alice), create = true)))
        commit(outsider, listOf(update("conversations/$otherThread", conversation(outsider), create = true)))
    }

    /** Both role-specific queries work, while an unrestricted list cannot expose another entrant's thread. */
    @Test fun membershipQueriesMatchTheAppAndRejectUnscopedReads() {
        val entrantRows = query(alice, "conversations", "entrantId", alice)
        assertTrue(entrantRows.contains("conversations/$thread\""))
        assertFalse(entrantRows.contains("conversations/$otherThread\""))
        val ownerRows = query(owner, "conversations", "ownerId", owner)
        assertTrue(ownerRows.contains("conversations/$thread\""))
        assertTrue(ownerRows.contains("conversations/$otherThread\""))
        assertFalse(query(alice, "conversations", "ownerId", alice).contains("\"document\""))
        expect(403, request("POST", "$root:runQuery", mapOf("structuredQuery" to mapOf(
            "from" to listOf(mapOf("collectionId" to "conversations")))), alice))
        expect(403, request("GET", "$root/conversations/$thread", uid = outsider))
    }

    /** The exact selected-thread message query works in both directions and cannot be read by a stranger. */
    @Test fun twoWayMessagesArePrivateAndDuplicateBatchesCannotResetANewerPreview() {
        val first = "first-$tag"
        val reply = "reply-$tag"
        commit(alice, send(first, alice, owner, "Hello"))
        commit(owner, send(reply, owner, alice, "Latest reply"))
        for (uid in listOf(alice, owner)) {
            val rows = query(uid, "messages", "conversationId", thread)
            assertTrue(rows.contains("messages/$first\"")); assertTrue(rows.contains("messages/$reply\""))
        }
        query(outsider, "messages", "conversationId", thread, expected = 403)
        commit(outsider, send("intrusion-$tag", outsider, owner, "Intrusion"), expected = 403)
        commit(alice, send(first, alice, owner, "Hello"), expected = 403)
        val saved = request("GET", "$root/conversations/$thread", uid = alice)
        expect(200, saved); assertTrue(saved.second.contains("Latest reply"))
    }

    /** Deleting the profile and its current owned image in the same commit is allowed by the real rules. */
    @Test fun profileAndCurrentPhotoCanBeDeletedAtomically() {
        commit(alice, listOf(mapOf("delete" to prefix + "photos/$photo"), mapOf("delete" to prefix + "users/$alice")))
        expect(404, request("GET", "$root/users/$alice"))
        expect(404, request("GET", "$root/photos/$photo"))
    }

    /** An unauthorized photo deletion rolls back the profile deletion in the same batch. */
    @Test fun failedPhotoCleanupDoesNotPartiallyDeleteAProfile() {
        val foreign = "foreign-$tag"
        seed("photos/$foreign", mapOf("ownerId" to outsider, "targetType" to "user", "targetId" to outsider,
            "imageBase64" to "amVwZw==", "mimeType" to "image/jpeg"))
        commit(alice, listOf(mapOf("delete" to prefix + "photos/$foreign"), mapOf("delete" to prefix + "users/$alice")), expected = 403)
        expect(200, request("GET", "$root/users/$alice"))
        expect(200, request("GET", "$root/photos/$photo"))
        expect(200, request("GET", "$root/photos/$foreign"))
    }

    /** Defines the existing conversation contract without a booking or lottery dependency. */
    private fun conversation(entrant: String) = mapOf("campsiteId" to site, "entrantId" to entrant, "ownerId" to owner,
        "lotteryId" to null, "reservationId" to null, "lastMessage" to "")

    /** Matches the app's atomic immutable-message and preview writes. */
    private fun send(id: String, sender: String, receiver: String, text: String) = listOf(
        update("messages/$id", mapOf("conversationId" to thread, "senderId" to sender, "receiverId" to receiver,
            "text" to text, "read" to false), create = true), update("conversations/$thread", mapOf("lastMessage" to text)))

    /** Builds typed REST document fields and server timestamp transforms. */
    private fun update(path: String, values: Map<String, Any?>, create: Boolean = false): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>("update" to mapOf("name" to prefix + path, "fields" to fields(values)),
            "updateTransforms" to (listOf(mapOf("fieldPath" to "updatedAt", "setToServerValue" to "REQUEST_TIME")) +
                if (create) listOf(mapOf("fieldPath" to "createdAt", "setToServerValue" to "REQUEST_TIME")) else emptyList()))
        if (!create) result["updateMask"] = mapOf("fieldPaths" to values.keys.toList())
        return result
    }

    /** Executes an authenticated atomic commit and checks the expected rule outcome. */
    private fun commit(uid: String, writes: List<Map<String, Any?>>, expected: Int = 200) {
        expect(expected, request("POST", "$root:commit", mapOf("writes" to writes), uid))
    }

    /** Uses the same equality predicates as the app, with no Admin SDK bypass for the query. */
    private fun query(uid: String, collection: String, field: String, value: String, expected: Int = 200): String {
        val filter = mapOf("fieldFilter" to mapOf(
            "field" to mapOf("fieldPath" to field), "op" to "EQUAL", "value" to mapOf("stringValue" to value)))
        val query = mapOf("from" to listOf(mapOf("collectionId" to collection)), "where" to filter)
        val result = request("POST", "$root:runQuery", mapOf("structuredQuery" to query), uid)
        expect(expected, result)
        return result.second
    }

    /** Creates isolated fixtures with the emulator's admin token before client-rule assertions. */
    private fun seed(path: String, values: Map<String, Any?>) {
        expect(200, request("POST", "$root:commit", mapOf("writes" to listOf(update(path, values, create = true)))))
    }
    /** Encodes only the field types used by these synthetic fixtures. */
    private fun fields(values: Map<String, Any?>) = values.mapValues { (_, value) -> when (value) {
        null -> mapOf("nullValue" to null)
        is Boolean -> mapOf("booleanValue" to value)
        else -> mapOf("stringValue" to value.toString())
    } }

    /** Calls the local REST engine with an emulated client identity or the fixture-only admin token. */
    private fun request(method: String, url: String, body: Any? = null, uid: String? = null): Pair<Int, String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${uid?.let(::token) ?: "owner"}")
            if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(json(body).toByteArray(Charsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { connection.disconnect() }
    }

    /** Produces the unsigned client token accepted only by the local Firestore emulator. */
    private fun token(uid: String): String {
        fun encode(value: Any) = Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).toByteArray(Charsets.UTF_8))
        val seconds = System.currentTimeMillis() / 1000
        return encode(mapOf("alg" to "none", "typ" to "JWT")) + "." + encode(mapOf("iss" to "https://securetoken.google.com/$project",
            "aud" to project, "sub" to uid, "user_id" to uid, "iat" to seconds, "exp" to seconds + 3600,
            "firebase" to mapOf("sign_in_provider" to "anonymous"))) + "."
    }

    /** Serializes controlled REST fixtures without relying on Android's stubbed JSON classes in JVM tests. */
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.flatMap { when (it) {
            '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> it.toString()
        }.toList() }.joinToString("") + "\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported test JSON value.")
    }

    /** Preserves the emulator's response body when a permission expectation fails. */
    private fun expect(status: Int, result: Pair<Int, String>) { assertEquals(result.second, status, result.first) }
}
