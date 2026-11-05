/* Offer UI tests inject every repository; no Firebase accounts or documents are changed. */
package com.example.legion_events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tests the real profile/list/detail routes with fake authenticated data. */
class BookingOfferNavigationTest {
    /** Compose host with a real navigation controller and lifecycle-owned screen controllers. */
    @get:Rule val compose = createComposeRule()
    /** Router inspected to verify encoded IDs and Back behavior. */
    private lateinit var navigation: NavHostController
    /** Saves and restores Compose/navigation state while retaining the entry's ViewModel. */
    private lateinit var restoration: StateRestorationTester

    /** Profile entry, confirmation cancellation, decline, restoration, and list refresh work together. */
    @Test fun profileOpensOffersAndConfirmedDeclinePersistsWhenReturning() {
        val repository = FakeOffers()
        open(repository)
        compose.onNodeWithText("Profile").performClick()
        waitFor("Lottery offers")
        compose.onNodeWithText("Lottery offers").performScrollTo().performClick()
        waitFor("View offer")
        compose.onNodeWithText("View offer").performClick()
        waitFor("Decline offer")
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
        compose.onNodeWithText("Back").performClick()
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

    /** Builds the actual router with fake data and rejects calls to unrelated owner/lottery features. */
    private fun open(offers: OfferRepository) {
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
            override suspend fun getLotteriesForOwner(ownerId: String): List<Lottery> = error("Unexpected owner lotteries")
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
            LegioneventsTheme {
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

    /** Mutable synthetic offer storage; its ID exercises navigation encoding. */
    private class FakeOffers : OfferRepository {
        /** Synthetic current offer; null simulates an unavailable document. */
        var offer: BookingOffer? = BookingOffer("offer #1?", "pine", "alice")
        /** Whether detail reads should fail. */
        var failRead = false
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
        override suspend fun getOffersForCurrentUser(): List<BookingOffer> = listOfNotNull(offer)
        /** Persists a fake decline after validating the routed document ID. */
        override suspend fun declineOffer(offerId: String): BookingOffer {
            if (failDecline) error("Offline")
            assertEquals(offerId, offer?.id)
            declines++
            return checkNotNull(offer).copy(status = BookingOffer.STATUS_DECLINED).also { offer = it }
        }
    }
}
