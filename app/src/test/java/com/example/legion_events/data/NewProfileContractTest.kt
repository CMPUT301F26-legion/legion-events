package com.example.legion_events.data

import com.google.firebase.firestore.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class NewProfileContractTest {
    @Test
    fun creationUsesExactlyTheDatabaseContractAndServerTimestamps() {
        assertEquals(
            mapOf(
                "name" to "",
                "email" to "",
                "phone" to "",
                "role" to "user",
                "disabled" to false,
                "photoId" to null,
                "fcmToken" to null,
                "notificationsEnabled" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            FirebaseRepository.newProfileFields(),
        )
    }
}
