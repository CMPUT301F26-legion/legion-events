/* Offer UI tests inject every repository; no Firebase accounts or documents are changed. */
package com.example.legion_events

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.bookingOfferRoute
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.OfferRepository
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.BookingOffer
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import com.example.legion_events.model.UserProfile
import com.example.legion_events.model.WaitlistEntrant
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Tests the real Lotteries/list/detail routes with fake authenticated data. */
class BookingOfferNavigationTest {
    /** Compose host with a real navigation controller and lifecycle-owned screen controllers. */
    @get:Rule val compose = createComposeRule()
    /** Router inspected to verify encoded IDs and Back behavior. */
    private lateinit var navigation: NavHostController
    /** Saves and restores Compose/navigation state while retaining the entry's ViewModel. */
    private lateinit var restoration: StateRestorationTester

    /** Lotteries entry, confirmation cancellation, decline, restoration, and list refresh work together. */
    @Test fun lotteriesOpenOffersAndConfirmedDeclinePersistsWhenReturning() {
        val repository = FakeOffers()
        open(repository)
        compose.onNodeWithText("Profile").performClick()
        waitFor("Profile & settings")
        compose.onNodeWithText("Lottery offers").assertDoesNotExist()
        compose.onNodeWithText("Lotteries").performClick()
        waitFor("My lotteries")
        compose.onNodeWithText("Lotteries").assertIsSelected()
        captureScreen("lotteries-overview-light")
        compose.onNodeWithText("View offers").performScrollTo().performClick()
        waitFor("View offer")
        compose.onNodeWithText("Lotteries").assertIsSelected()
        captureScreen("offer-list-light")
        compose.onNodeWithText("View offer").performClick()
        waitFor("Decline offer")
        compose.onNodeWithText("Lotteries").assertIsSelected()
        captureScreen("offer-detail-light")
        compose.runOnIdle {
            assertEquals(repository.offer!!.id, navigation.currentBackStackEntry?.arguments?.getString("offerId"))
        }
        compose.onNodeWithText("Decline offer").performClick()
        compose.onNodeWithText("Keep offer").performClick()
        compose.runOnIdle { assertEquals(0, repository.declines) }
        compose.onNodeWithText("Decline offer").performClick()
        compose.onNodeWithText("Confirm decline").performClick()
        waitFor("You declined this offer.")
        compose.onNodeWithText("Decline offer").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        waitFor("You declined this offer.")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Declined")
        compose.runOnIdle { assertEquals(1, repository.declines) }
    }

    /** Read and action failures can be retried, and a missing offer exposes no decline button. */
    @Test fun directOfferRouteHandlesReadAndActionFailuresThenMissingOffer() {
        val repository = FakeOffers().apply { failRead = true }
        open(repository)
        compose.runOnIdle { navigation.navigate(bookingOfferRoute(repository.offer!!.id)) }
        waitFor("Could not load this offer. Check your connection and retry.")
        compose.onNodeWithText("Decline offer").assertDoesNotExist()
        compose.runOnIdle { repository.failRead = false; repository.failDecline = true }
        compose.onNodeWithText("Retry").performClick()
        waitFor("Decline offer")
        compose.onNodeWithText("Decline offer").performClick()
        compose.onNodeWithText("Confirm decline").performClick()
        waitFor("Could not confirm the decline. Reload the offer or try declining again.")
        compose.runOnIdle { repository.offer = null }
        compose.onNodeWithText("Reload offer").performClick()
        waitFor("This offer is no longer available.")
        compose.onNodeWithText("Decline offer").assertDoesNotExist()
    }

    /** An expired response deadline prevents a decline even when stored status is still open. */
    @Test fun elapsedOfferCannotBeDeclined() {
        val repository = FakeOffers().apply { offer = offer!!.copy(expiresAt = Timestamp(1, 0)) }
        open(repository)
        compose.runOnIdle { navigation.navigate(bookingOfferRoute(repository.offer!!.id)) }
        waitFor("Expired")
        compose.onNodeWithText("Decline offer").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, repository.declines) }
    }

    /** Failed and empty lists can reload real responses without inserting demo offers. */
    @Test fun failedAndEmptyListsCanRefresh() {
        val repository = FakeOffers().apply { failList = true }
        open(repository)
        compose.onNodeWithText("Lotteries").performClick()
        waitFor("View offers")
        compose.onNodeWithText("View offers").performClick()
        waitFor("Could not load your offers. Check your connection and retry.")
        compose.runOnIdle { repository.failList = false; repository.offer = null }
        compose.onNodeWithText("Retry").performClick()
        waitFor("You have no lottery offers yet.")
        compose.onNodeWithText("Lotteries").assertIsSelected()
        captureScreen("offer-list-empty-light")
        compose.runOnIdle { repository.offer = BookingOffer("another", "oak", "alice") }
        compose.onNodeWithText("Refresh offers").performClick()
        waitFor("View offer")
    }

    /** Offer details retain their Lotteries tab and saved navigation state in the dark palette. */
    @Test fun offerDetailsRestoreUnderLotteriesInDarkMode() {
        val repository = FakeOffers()
        open(repository, darkTheme = true)
        compose.onNodeWithText("Lotteries").performClick()
        waitFor("View offers")
        compose.onNodeWithText("View offers").performClick()
        waitFor("View offer")
        compose.onNodeWithText("View offer").performClick()
        waitFor("Decline offer")
        compose.onNodeWithText("Lotteries").assertIsSelected()
        compose.onNodeWithText("Profile").performClick()
        waitFor("Profile & settings")
        compose.onNodeWithText("Lotteries").performClick()
        waitFor("Decline offer")
        compose.onNodeWithText("Lotteries").assertIsSelected()
        captureScreen("offer-detail-dark")
    }

    /** Builds the actual router with fake data and rejects calls to unrelated owner/lottery features. */
    private fun open(offers: OfferRepository, darkTheme: Boolean = false) {
        lateinit var onboarding: OnboardingController
        compose.runOnUiThread {
            onboarding = OnboardingController(object : UserRepository {
                /** Supplies a synthetic signed-in account without Firebase Auth. */
                override suspend fun initializeUser() = UserProfile("alice", name = "Alice")
            })
        }
        val campsites = object : CampsiteRepository {
            /** No campsites are needed to exercise profile/offer navigation. */
            override fun observeCampsites() = flowOf(emptyList<Campsite>())
        }
        val details = object : CampsiteDetailsRepository {
            /** These tests never open a campsite detail route. */
            override suspend fun getCampsite(campsiteId: String): Campsite? = error("Unexpected campsite read")
            /** These tests never request campsite photos. */
            override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?): List<Photo> = error("Unexpected photo read")
        }
        val profiles = object : ProfileRepository {
            /** Returns a synthetic profile so the real Profile screen can render its offers link. */
            override suspend fun getProfile() = UserProfile("alice", name = "Alice")
            /** Contact editing is outside these tests. */
            override suspend fun updateContact(name: String, email: String, phone: String): Unit = error("Unexpected edit")
            /** Notification settings are outside these tests. */
            override suspend fun setNotificationsEnabled(enabled: Boolean): Unit = error("Unexpected preference write")
            /** Account deletion is outside these tests. */
            override suspend fun deleteProfile(): Unit = error("Unexpected delete")
        }
        val lotteries = object : LotteryRepository {
            /** Offer screens must not create lotteries. */
            override suspend fun createLottery(lottery: Lottery): String = error("Unexpected lottery creation")
            /** Offer screens must not change lotteries. */
            override suspend fun updateLottery(lottery: Lottery): Unit = error("Unexpected lottery edit")
            /** Offer screens use their own repository to load offers. */
            override suspend fun getLottery(id: String): Lottery = error("Unexpected lottery read")
            /** Owner lottery listings are outside these tests. */
            override suspend fun getMyLotteries(): List<Lottery> = error("Unexpected owner lotteries")
            /** Owner waitlists are outside these tests. */
            override suspend fun getWaitlist(lotteryId: String): List<WaitlistEntrant> = error("Unexpected waitlist read")
        }
        val owners = object : OwnerCampsiteRepository {
            /** Owner listings are outside these tests. */
            override suspend fun getMyCampsites(): List<Campsite> = error("Unexpected owner listings")
            /** Owner details are outside these tests. */
            override suspend fun getMyCampsite(campsiteId: String): Campsite? = error("Unexpected owner details")
            /** Owner lottery badges are outside these tests. */
            override suspend fun getMyLotteries(): List<Lottery> = error("Unexpected owner lotteries")
            /** Owner photos are outside these tests. */
            override suspend fun getPhotos(photoIds: List<String>): List<Photo> = error("Unexpected owner photos")
            /** Offer declines must not save campsites. */
            override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?): String =
                error("Unexpected campsite save")
        }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            navigation = rememberNavController()
            LegioneventsTheme(darkTheme = darkTheme) {
                AppNavigation(onboarding, navigation, campsites, details, profiles,
                    lotteries = lotteries, owners = owners, offers = offers)
            }
        }
        waitFor("Explore campsites")
    }

    /** Waits until a controller's asynchronous result is reflected in the composed screen. */
    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Saves fake-data previews in the test app's cache only when captureOfferScreens is requested. */
    private fun captureScreen(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureOfferScreens") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    /** Mutable synthetic offer storage; its ID exercises navigation encoding. */
    private class FakeOffers : OfferRepository {
        /** Synthetic current offer; null simulates an unavailable document. */
        var offer: BookingOffer? = BookingOffer("offer #1?", "pine", "alice",
            createdAt = Timestamp(1_793_836_800L, 0), expiresAt = Timestamp(4_070_908_800L, 0))
        /** Whether detail reads should fail. */
        var failRead = false
        /** Whether list reads should fail independently of detail reads. */
        var failList = false
        /** Whether decline attempts should fail. */
        var failDecline = false
        /** Count of successfully persisted fake declines. */
        var declines = 0
        /** Returns the selected synthetic record or a controlled read failure. */
        override suspend fun getOffer(offerId: String): BookingOffer? {
            if (failRead) error("Offline")
            return offer?.takeIf { it.id == offerId }
        }
        /** Returns only the synthetic account's records. */
        override suspend fun getOffersForCurrentUser(): List<BookingOffer> {
            if (failList) error("Offline")
            return listOfNotNull(offer)
        }
        /** Persists a fake decline after validating the routed document ID. */
        override suspend fun declineOffer(offerId: String): BookingOffer {
            if (failDecline) error("Offline")
            assertEquals(offerId, offer?.id)
            declines++
            return checkNotNull(offer).copy(status = BookingOffer.STATUS_DECLINED).also { offer = it }
        }
    }
}
