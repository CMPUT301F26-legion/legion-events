/*
 * ReservationConfirmationTest.kt
 *
 * Purpose: Verify confirmation navigation and CSV saves, cancellation, and failures.
 * Role: Android UI tests using the shared router, fake reservations, and a document picker registry.
 * Outstanding issues: None.
 */
package com.example.legion_events

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.reservationConfirmationRoute
import com.example.legion_events.controller.reservationDetailsRoute
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.ReservationEntry
import com.example.legion_events.data.ReservationRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses real file output and intercepted Save intents without contacting Firebase. */
class ReservationConfirmationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    // The existing cache provider gives this isolated test a writable content URI.
    private val receipt = File(context.cacheDir, "qr/confirmation-test.csv")
    private val picker = DocumentPicker()
    private lateinit var navigation: NavHostController
    private lateinit var restoration: StateRestorationTester
    private val entry = ReservationEntry(Reservation("booking #1", "lottery", "offer", "pine", "alice", "owner",
        Timestamp(100, 0), Timestamp(200, 0), 2, 6550, true, "confirmed"), "Pine Ridge", "Québec")

    @After fun cleanUp() { receipt.delete() }

    @Test fun detailsOpenConfirmationAndBothScreensSaveUsingTheDocumentContract() {
        val repository = FakeReservations()
        open(repository)
        compose.onNodeWithText("View confirmation").performScrollTo().performClick()
        waitFor("Reservation confirmed")
        compose.onNodeWithText("Bookings").assertIsSelected()
        compose.onNodeWithText("Reservation booking #1").assertIsDisplayed()
        picker.destination = receiptUri()
        compose.onNodeWithText("Download confirmation CSV").performScrollTo().performClick()
        waitFor("Confirmation CSV saved.")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent?.action)
        assertEquals("text/csv", picker.intent?.type)
        assertTrue(checkNotNull(picker.intent?.getStringExtra(Intent.EXTRA_TITLE)).endsWith(".csv"))
        val csv = receipt.readText(Charsets.UTF_8)
        assertTrue(csv.contains("\"booking #1\",\"pine\",\"Pine Ridge\",\"Québec\""))
        assertTrue(csv.contains("\"65.50\",\"CAD\",\"confirmed\""))
        val reads = compose.runOnIdle { repository.reads }
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Confirmation CSV saved.")
        compose.runOnIdle {
            assertEquals(reads, repository.reads)
            assertEquals(1, picker.launches)
        }
        compose.onNodeWithText("View reservation").performScrollTo().performClick()
        waitFor("Total: $65.50 CAD")
        compose.runOnIdle { repository.saved = entry.copy(reservation = entry.reservation.copy(totalPrice = 12345)) }
        compose.onNodeWithText("Download confirmation CSV").performScrollTo().performClick()
        waitFor("Confirmation CSV saved.")
        assertTrue(receipt.readText().contains("\"123.45\""))
        compose.runOnIdle { assertEquals(2, picker.launches) }
    }

    @Test fun cancellingDoesNotExportAndFailedWritesCanBeRetriedInDarkMode() {
        val repository = FakeReservations()
        open(repository, confirmation = true, dark = true)
        val reads = compose.runOnIdle { repository.reads }
        compose.onNodeWithText("Download confirmation CSV").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(reads, repository.reads) }
        compose.onNodeWithText("Confirmation CSV saved.").assertDoesNotExist()
        picker.destination = Uri.parse("content://unavailable.documents/receipt.csv")
        compose.onNodeWithText("Download confirmation CSV").performScrollTo().performClick()
        waitFor("Could not save the confirmation CSV. Try downloading again.")
        compose.onNodeWithText("Download confirmation CSV").assertIsEnabled()
        picker.destination = receiptUri()
        compose.onNodeWithText("Download confirmation CSV").performScrollTo().performClick()
        waitFor("Confirmation CSV saved.")
        assertTrue(receipt.readText().contains("\"65.50\""))
    }

    @Test fun unconfirmedDirectLinksDoNotShowSuccessOrAllowDownloads() {
        open(FakeReservations().apply { saved = entry.copy(reservation = entry.reservation.copy(status = "cancelled")) },
            confirmation = true)
        waitFor("This reservation is not confirmed.")
        compose.onNodeWithText("Reservation confirmed").assertDoesNotExist()
        compose.onNodeWithText("Download confirmation CSV").assertDoesNotExist()
    }

    @Test fun confirmationWaitsForOwnershipAndHandlesReadErrorsAndMissingRecords() {
        val pending = CompletableDeferred<ReservationEntry?>()
        val repository = FakeReservations().apply { load = { pending.await() } }
        open(repository, confirmation = true, waitForBooking = false)
        waitFor("Loading confirmation…")
        compose.onNodeWithText("Reservation confirmed").assertDoesNotExist()
        compose.onNodeWithText("Download confirmation CSV").assertDoesNotExist()
        pending.completeExceptionally(SecurityException("Not owned"))
        waitFor("Could not load this reservation. Check your connection and retry.")
        compose.runOnIdle { repository.load = { null } }
        compose.onNodeWithText("Retry").performClick()
        waitFor("This reservation is not available for this account.")
        compose.onNodeWithText("Download confirmation CSV").assertDoesNotExist()
        compose.runOnIdle { repository.load = { entry } }
        compose.onNodeWithText("Retry").performClick()
        waitFor("Reservation confirmed")
    }

    /** Starts the shared router, passing only the document ID to either reservation screen. */
    private fun open(repository: FakeReservations, confirmation: Boolean = false,
        dark: Boolean = false, waitForBooking: Boolean = true) {
        receipt.delete()
        val account = OnboardingController(object : UserRepository {
            override suspend fun initializeUser() = UserProfile("alice")
        })
        val campsites = object : CampsiteRepository { override fun observeCampsites() = flowOf(emptyList<Campsite>()) }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                navigation = rememberNavController()
                LegioneventsTheme(darkTheme = dark) {
                    AppNavigation(account, navigation, campsites = campsites, reservations = repository)
                }
            }
        }
        waitFor("Explore campsites")
        compose.runOnIdle {
            navigation.navigate(if (confirmation) reservationConfirmationRoute(entry.reservation.id)
                else reservationDetailsRoute(entry.reservation.id))
        }
        if (waitForBooking) waitFor(if (confirmation && repository.saved?.reservation?.status == "confirmed")
            "Reservation confirmed" else if (confirmation) "This reservation is not confirmed." else "Total: $65.50 CAD")
    }

    /** Returns a writable URI for the test receipt through the app's existing cache provider. */
    private fun receiptUri(): Uri {
        check(receipt.parentFile!!.mkdirs() || receipt.parentFile!!.isDirectory)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", receipt)
    }

    /** Waits for repository reads or file saves to be reflected in the screen. */
    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Captures the platform document intent and supplies a chosen URI or cancellation. */
    private inner class DocumentPicker : ActivityResultRegistry() {
        var destination: Uri? = null
        var intent: Intent? = null
        var launches = 0

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            launches++
            intent = contract.createIntent(context, input)
            dispatchResult(requestCode, if (destination == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                destination?.let { Intent().setData(it) })
        }
    }

    /** Supplies only owned synthetic bookings and counts checks before exporting. */
    private inner class FakeReservations : ReservationRepository {
        var saved: ReservationEntry? = entry
        var load: suspend () -> ReservationEntry? = { saved }
        var reads = 0
        override suspend fun getMyReservations(): List<ReservationEntry> = error("Unexpected list read")
        override suspend fun getReservation(reservationId: String): ReservationEntry? {
            assertEquals(entry.reservation.id, reservationId)
            reads++
            return load()
        }
    }
}
