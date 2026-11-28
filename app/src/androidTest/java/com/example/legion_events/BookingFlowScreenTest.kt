/* BookingFlowScreenTest.kt: exercises real Compose controls and the controller using synthetic data only. */
package com.example.legion_events

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.controller.BookingFlowController
import com.example.legion_events.data.BookingRepository
import com.example.legion_events.model.*
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.example.legion_events.view.BookingFlowActions
import com.example.legion_events.view.BookingFlowScreen
import com.google.firebase.Timestamp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
class BookingFlowScreenTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var controller: BookingFlowController
    private val now = Timestamp.now()
    private val start = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time = now.toDate(); set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.time.time
    private val repository = FakeBookings()

    @After fun close() { compose.runOnUiThread { store.clear() } }

    @Test fun selectDatesAcceptPoliciesReviewThenSubmitShowsPendingRatherThanConfirmed() {
        open()
        chooseStay()
        compose.onNodeWithText("Review reservation").assertIsNotEnabled()
        scrollTo(hasText("Quiet after 10pm", substring = true)).assertIsDisplayed()
        scrollTo(hasText("Cancel before arrival.")).assertIsDisplayed()
        agree()
        capture("booking-selection-light")
        compose.onNodeWithText("Review reservation").performClick()
        waitFor("Confirm your reservation request")
        compose.runOnIdle { assertEquals(0, repository.writes) }
        compose.onNodeWithText("Back to selection").performClick()
        compose.runOnIdle { assertEquals(0, repository.writes) }
        compose.onNodeWithText("Review reservation").performClick()
        waitFor("Confirm your reservation request")
        capture("booking-review-light", dialog = true)
        compose.onNodeWithText("Confirm reservation").performClick()
        waitFor("Awaiting confirmation")
        compose.onNodeWithText("Reservation confirmed").assertDoesNotExist()
        compose.onNodeWithText("Request reference: ui-attempt").assertExists()
        compose.runOnIdle {
            assertEquals(1, repository.writes)
            assertEquals(1, repository.accepted!!.guestCount)
            assertEquals(1555L, repository.accepted!!.totalPrice)
            assertEquals(start + 86_400_000, repository.accepted!!.endDate.toDate().time)
        }
    }

    @Test fun rolloutGateAllowsReviewButDisablesTheFinalSubmitButton() {
        open(enabled = false)
        chooseStay(); agree()
        compose.onNodeWithText("Review reservation").performClick()
        waitFor("Confirm your reservation request")
        compose.onNodeWithText("Confirm reservation").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, repository.writes) }
    }

    @Test fun changingGuestsClearsAgreementAndDarkThemeKeepsTheSameFlow() {
        open(dark = true)
        chooseStay(); agree()
        compose.onNodeWithText("Review reservation").assertIsEnabled()
        scrollTo(hasContentDescription("Increase guests")).performClick()
        compose.onNodeWithText("Review reservation").assertIsNotEnabled()
        agree()
        compose.onNodeWithText("Review reservation").assertIsEnabled()
        capture("booking-selection-dark")
        compose.runOnIdle { assertEquals(0, repository.writes) }
    }

    @Test fun noAvailableDatesOffersRefreshWithoutASubmissionPath() {
        repository.unavailable = true
        open()
        waitFor("No campsite dates are currently available.")
        compose.onNodeWithText("Review reservation").assertIsNotEnabled()
        scrollTo(hasText("Refresh booking choices")).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, repository.writes) }
    }

    /** Opens the real controller and view without Firebase or a production identity. */
    private fun open(enabled: Boolean = true, dark: Boolean = false) {
        compose.runOnUiThread {
            controller = ViewModelProvider.create(store, viewModelFactory {
                initializer { BookingFlowController("draw_alice", "alice", repository, SavedStateHandle(), enabled, { now }, { "ui-attempt" }) }
            })[BookingFlowController::class]
        }
        compose.setContent {
            val state by controller.state.collectAsState()
            LegioneventsTheme(darkTheme = dark) {
                BookingFlowScreen(state, BookingFlowActions({}, controller::reload, controller::selectCampsite,
                    controller::selectCheckIn, controller::selectCheckOut, controller::selectGuests,
                    controller::acceptRules, controller::review, controller::dismissReview, controller::confirm,
                    controller::retrySubmission, controller::chooseAgain, {}))
            }
        }
        waitFor("Choose your stay")
    }

    /** Uses both real date-picker dialogs, with checkout on the day after the selected night. */
    private fun chooseStay() {
        scrollTo(hasText("Pine A12")).performClick()
        scrollTo(hasTestTag("booking-check-in")).performClick()
        chooseCalendarDay(start)
        compose.onNodeWithText("Use date").performClick()
        scrollTo(hasTestTag("booking-check-out")).performClick()
        chooseCalendarDay(start + 86_400_000)
        compose.onNodeWithText("Use date").performClick()
    }

    /** Material exposes full accessible date labels rather than the decorative day numeral. */
    private fun chooseCalendarDay(millis: Long) {
        val label = checkNotNull(DatePickerDefaults.dateFormatter().formatDate(millis, Locale.getDefault(), forContentDescription = true))
        compose.onNode(hasText(label, substring = true) and hasClickAction()).performClick()
    }

    /** Explicitly agrees to the displayed policies, never through a default checked state. */
    private fun agree() {
        scrollTo(hasText("I agree to the campsite rules and cancellation policy.")).performClick()
    }

    /** Lazy items must be scrolled into composition before querying their individual controls. */
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("booking-choices").performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    /** Waits for asynchronous controller work to reach the rendered screen. */
    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Optional fake-data preview captured only into the instrumentation app's cache. */
    private fun capture(name: String, dialog: Boolean = false) {
        if (InstrumentationRegistry.getArguments().getString("captureBookingScreens") != "true") return
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Current availability and immutable writes are local synthetic records only. */
    private inner class FakeBookings : BookingRepository {
        var unavailable = false
        var writes = 0
        var accepted: BookingRequest? = null
        private var saved: Reservation? = null
        override suspend fun getRequestsForOffer(offerId: String) = listOfNotNull(saved)
        override suspend fun getBookingOptions(offerId: String): BookingOptions {
            val expiry = Timestamp(now.seconds + 86_400, 0)
            val site = Campsite("pine", "owner", "Pine A12", "Alberta", 3, 1555,
                rules = listOf("Quiet after 10pm"), cancellationPolicy = "Cancel before arrival.",
                availableNights = if (unavailable) emptyList() else bookingNightKeys(
                    Timestamp(java.util.Date(start)), Timestamp(java.util.Date(start + 3 * 86_400_000))))
            return BookingOptions(BookingOffer(offerId, "draw", "alice", expiresAt = expiry, campsiteIds = listOf("pine")),
                Lottery("draw", "owner", listOf("pine"), Timestamp(1, 0), Timestamp(2, 0), Timestamp(3, 0), expiry,
                    1, winnersDrawn = true), listOf(BookingCampsite(site, emptySet())))
        }
        override suspend fun acceptOffer(request: BookingRequest): Reservation {
            writes++; accepted = request
            return Reservation(request.id, "draw", request.offerId, request.campsiteId, "alice", "owner",
                request.startDate, request.endDate, request.guestCount, request.totalPrice, true, Reservation.STATUS_REQUESTED)
                .also { saved = it }
        }
    }
}
