package com.example.legion_events.data

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Creates a missing profile when deployed rules deny reads before it exists. */
internal class FirestoreProfileCreator {
    suspend fun createIfAbsent(
        user: FirebaseUser,
        projectId: String,
        fields: Map<String, Any?>,
    ) {
        val token = user.getIdToken(false).await().token
            ?: throw UserInitializationException("Could not authenticate profile registration. Please retry.")
        val database = "projects/$projectId/databases/(default)"
        val request = createRequest(database, user.uid, fields)
        withContext(Dispatchers.IO) {
            val connection = URL(
                "https://firestore.googleapis.com/v1/$database/documents:commit",
            ).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use {
                    it.write(JSONObject(request).toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status in 200..299) {
                    connection.inputStream.close()
                    return@withContext
                }
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val code = response?.let { JSONObject(it).optJSONObject("error")?.optString("status") }
                // Another caller may have created the profile while this request was in flight.
                if (code == "ALREADY_EXISTS") return@withContext
                val message = if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                    "Your profile could not be registered. Retry, or contact the app administrator if this continues."
                } else {
                    "Could not finish profile registration. Check your connection and retry."
                }
                throw UserInitializationException(message)
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        internal fun createRequest(
            database: String,
            uid: String,
            fields: Map<String, Any?>,
        ): Map<String, Any> {
            val documentFields = mutableMapOf<String, Any>()
            val transforms = mutableListOf<Map<String, String>>()
            fields.forEach { (name, value) ->
                when (value) {
                    null -> documentFields[name] = mapOf("nullValue" to null)
                    is String -> documentFields[name] = mapOf("stringValue" to value)
                    is Boolean -> documentFields[name] = mapOf("booleanValue" to value)
                    is FieldValue -> {
                        require(value == FieldValue.serverTimestamp())
                        transforms += mapOf("fieldPath" to name, "setToServerValue" to "REQUEST_TIME")
                    }
                    else -> error("Unsupported initial profile field: $name")
                }
            }
            return mapOf(
                "writes" to listOf(
                    mapOf(
                        "update" to mapOf(
                            "name" to "$database/documents/users/$uid",
                            "fields" to documentFields,
                        ),
                        // Android's SDK has no public create-only write. REST enforces this
                        // atomically on the server, still using the user's security rules.
                        "currentDocument" to mapOf("exists" to false),
                        "updateTransforms" to transforms,
                    ),
                ),
            )
        }
    }
}
