package com.example.legion_events.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FirestoreProfileCreatorTest {
    @Test
    fun firstLaunchWriteCannotOverwriteAndUsesExactContractWithServerTimes() {
        val database = "projects/test-project/databases/(default)"
        val request = FirestoreProfileCreator.createRequest(
            database,
            "authenticated-uid",
            FirebaseRepository.newProfileFields(),
        )
        assertEquals(
            mapOf(
                "writes" to listOf(
                    mapOf(
                        "update" to mapOf(
                            "name" to "$database/documents/users/authenticated-uid",
                            "fields" to mapOf(
                                "name" to mapOf("stringValue" to ""),
                                "email" to mapOf("stringValue" to ""),
                                "phone" to mapOf("stringValue" to ""),
                                "role" to mapOf("stringValue" to "user"),
                                "disabled" to mapOf("booleanValue" to false),
                                "photoId" to mapOf("nullValue" to null),
                                "fcmToken" to mapOf("nullValue" to null),
                                "notificationsEnabled" to mapOf("booleanValue" to true),
                            ),
                        ),
                        "currentDocument" to mapOf("exists" to false),
                        "updateTransforms" to listOf(
                            mapOf("fieldPath" to "createdAt", "setToServerValue" to "REQUEST_TIME"),
                            mapOf("fieldPath" to "updatedAt", "setToServerValue" to "REQUEST_TIME"),
                        ),
                    ),
                ),
            ),
            request,
        )
    }
}
