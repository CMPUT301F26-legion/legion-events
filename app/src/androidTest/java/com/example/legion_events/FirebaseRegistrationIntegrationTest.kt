package com.example.legion_events

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.data.FirebaseRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/** Opt-in live check: uses a separate, persistent test identity in the configured project. */
class FirebaseRegistrationIntegrationTest {
    @Test
    fun freshRegistrationAndConcurrentRetriesPreserveOneProfile() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("verifyFirebaseRegistration") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = "onboarding-integration-check"
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, appName)
        val auth = FirebaseAuth.getInstance(app)
        val firestore = FirebaseFirestore.getInstance(app)
        val repository = FirebaseRepository(auth, firestore)

        // Start multiple initializations before there is an authenticated user or profile.
        val profiles = coroutineScope {
            List(8) { async { repository.initializeUser() } }.awaitAll()
        }
        val uid = checkNotNull(auth.currentUser).uid
        assertTrue(checkNotNull(auth.currentUser).isAnonymous)
        assertEquals(setOf(uid), profiles.map { it.uid }.toSet())
        val document = firestore.collection("users").document(uid)
        val initial = document.get(Source.SERVER).await()
        assertTrue(initial.exists())
        assertEquals(
            setOf("name", "email", "phone", "role", "disabled", "photoId", "fcmToken",
                "notificationsEnabled", "createdAt", "updatedAt"),
            initial.data?.keys,
        )
        for (field in listOf("name", "email", "phone")) assertEquals("", initial.getString(field))
        assertEquals("user", initial.getString("role"))
        assertFalse(checkNotNull(initial.getBoolean("disabled")))
        assertTrue(checkNotNull(initial.getBoolean("notificationsEnabled")))
        assertEquals(null, initial.get("photoId"))
        assertEquals(null, initial.get("fcmToken"))
        assertNotNull(initial.getTimestamp("createdAt"))
        assertNotNull(initial.getTimestamp("updatedAt"))

        repeat(3) { repository.initializeUser() }
        assertEquals(uid, auth.currentUser?.uid)
        assertEquals(initial.data, document.get(Source.SERVER).await().data)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(checkNotNull(initial.data).toSortedMap().toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", "Verified UID: $uid; profile fingerprint: $fingerprint\n") },
        )
    }
}
