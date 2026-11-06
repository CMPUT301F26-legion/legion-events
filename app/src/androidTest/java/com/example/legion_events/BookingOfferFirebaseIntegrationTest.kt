/*
 * BookingOfferFirebaseIntegrationTest.kt
 *
 * Purpose: Verify that an owned offer can be declined under the configured Firestore rules.
 * Scope: An opt-in Android test using a separate test identity and a seeded offer.
 */
package com.example.legion_events

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.data.FirebaseOfferRepository
import com.example.legion_events.data.FirebaseRepository
import com.example.legion_events.model.BookingOffer
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the live decline transaction against a designated test project's rules. */
class BookingOfferFirebaseIntegrationTest {
    @Test
    fun prepareTestUser() = runBlocking {
        val app = testApp()
        val auth = FirebaseAuth.getInstance(app)
        val firestore = FirebaseFirestore.getInstance(app)
        val profile = FirebaseRepository(auth, firestore).initializeUser()

        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", "Booking offer test UID: ${profile.uid}\n") },
        )
    }

    @Test
    fun decliningOwnedOfferPersistsAndRetryPreservesTimestamp() = runBlocking {
        val app = testApp()
        val offerId = InstrumentationRegistry.getArguments().getString("offerTestId").orEmpty()
        require(offerId.matches(Regex("decline-test-[A-Za-z0-9_-]+"))) {
            "Pass the ID of a fresh decline-test-* fixture as offerTestId."
        }
        val auth = FirebaseAuth.getInstance(app)
        val user = checkNotNull(auth.currentUser) { "Run prepareTestUser on this device first." }
        check(user.isAnonymous) { "The offer test requires its dedicated anonymous user." }

        val firestore = FirebaseFirestore.getInstance(app)
        val repository = FirebaseOfferRepository(auth, firestore)
        val document = firestore.collection("offers").document(offerId)
        val before = document.get(Source.SERVER).await()
        assertTrue("Seed offers/" + offerId + " before running this test.", before.exists())
        assertEquals("The fixture must belong to the test user.", user.uid, before.getString("userId"))
        assertEquals("Seed a fresh pending offer.", "pending", before.getString("status"))
        assertEquals("The fixture must be unanswered.", null, before.get("response"))
        assertTrue("The owned offer should appear in the list.", repository.getOffersForCurrentUser().any {
            it.id == offerId && it.status == BookingOffer.STATUS_OPEN
        })

        val saved = repository.declineOffer(offerId)
        val after = document.get(Source.SERVER).await()
        val updatedAt = checkNotNull(after.getTimestamp("updatedAt")) { "Decline must set updatedAt." }
        assertEquals(BookingOffer.STATUS_DECLINED, saved.status)
        assertEquals("pending", after.getString("status"))
        assertEquals("decline", after.getString("response"))
        assertEquals(updatedAt, after.getTimestamp("respondedAt"))
        assertEquals(updatedAt, saved.updatedAt)
        assertNotEquals(before.getTimestamp("updatedAt"), updatedAt)
        val changedFields = setOf("response", "respondedAt", "updatedAt")
        assertEquals(before.data.orEmpty() - changedFields, after.data.orEmpty() - changedFields)

        val retried = repository.declineOffer(offerId)
        assertEquals(updatedAt, retried.updatedAt)
        assertEquals(after.data, document.get(Source.SERVER).await().data)
    }

    /** Reuses a test-only identity across the two invocations on the same device. */
    private fun testApp(): FirebaseApp {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyFirebaseOfferDecline") == "true")
        val expectedProject = arguments.getString("offerTestProject").orEmpty()
        val options = FirebaseApp.getInstance().options
        require(expectedProject.isNotBlank() && expectedProject == options.projectId) {
            "Pass offerTestProject matching the designated test project configured for the app."
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = "booking-offer-integration-check"
        return FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(context, options, appName)
    }
}
