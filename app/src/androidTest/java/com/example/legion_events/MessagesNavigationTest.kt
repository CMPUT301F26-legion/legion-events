/*
 * MessagesNavigationTest.kt
 *
 * Purpose: Verify private chat entry, owner replies, drafts, and uncertain-send retries.
 * Role: Android UI tests through AppNavigation using live in-memory message streams.
 * Outstanding issues: None.
 */
package com.example.legion_events

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.*
import com.example.legion_events.data.*
import com.example.legion_events.model.*
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

/** Uses synthetic participants; announcements and real Firebase services are never called. */
class MessagesNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var navigation: NavHostController
    private lateinit var restoration: StateRestorationTester
    private val repository = FakeMessages()
    private val site = Campsite("pine #1", "owner", "Pine Ridge", "Banff", 4, 5000)

    @Test fun campsiteOpensPrivateChatAndBothSidesMessagesAndDraftSurviveRecreation() {
        open()
        compose.runOnIdle { navigation.navigate("campsite/${android.net.Uri.encode(site.id)}") }
        waitFor("Message owner")
        compose.onNodeWithText("Message owner").performScrollTo().performClick()
        waitFor("Message")
        compose.onNodeWithText("Message").performTextInput("Hello owner")
        compose.onNodeWithText("Send").performClick()
        waitFor("Hello owner")
        compose.runOnIdle { repository.reply("Welcome entrant") }
        waitFor("Welcome entrant")
        compose.onNodeWithText("Message").performTextInput("Unsent draft")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Unsent draft")
        compose.runOnIdle { assertEquals(1, repository.sends); assertEquals(1, repository.starts) }
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Message owner")
        compose.runOnIdle { assertEquals(0, repository.listeners) }
    }

    @Test fun ownerOpensMessagesFromProfileAndRepliesWithoutChangingAnnouncements() {
        open(asOwner = true, dark = true)
        compose.onNodeWithText("Profile").performClick()
        waitFor("Messages")
        compose.onNodeWithText("Messages").performScrollTo().performClick()
        waitFor("Open conversation")
        compose.onNodeWithText("Chat with entrant").assertIsDisplayed()
        compose.onNodeWithText("Open conversation").performClick()
        waitFor("Message")
        compose.onNodeWithText("Message").performTextInput("Owner reply")
        compose.onNodeWithText("Send").performClick()
        waitFor("Owner reply")
        compose.runOnIdle {
            assertEquals("owner", repository.messages.value.single().senderId)
            assertEquals("alice", repository.messages.value.single().receiverId)
        }
    }

    @Test fun ownerSeesMessagedPeopleFirstAndUnmessagedEntrantsAfter() {
        repository.messages.value = listOf(ChatMessage("hi", "thread", "alice", "owner", "Hi owner", false, Timestamp.now(), Timestamp.now()))
        open(asOwner = true)
        compose.runOnIdle { navigation.navigate(MESSAGES_ROUTE) }
        waitFor("People")
        waitFor("Entrant …bob")
        val alice = compose.onNodeWithText("Entrant …alice").fetchSemanticsNode().boundsInRoot.top
        val bob = compose.onNodeWithText("Entrant …bob").fetchSemanticsNode().boundsInRoot.top
        assertTrue(alice < bob)
        compose.onNodeWithText("Booked your campsite · not messaged yet").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        waitFor("Message")
        compose.onNodeWithText("Hi owner").assertIsDisplayed()
    }

    @Test fun failedSendsKeepTheirIdAcrossRecreationAndRetryDoesNotDuplicateTheMessage() {
        open()
        compose.runOnIdle { navigation.navigate(chatRoute("thread")) }
        waitFor("Message")
        repository.failSend = true
        compose.onNodeWithText("Message").performTextInput("Please confirm")
        compose.onNodeWithText("Send").performClick()
        waitFor("Retry send")
        compose.onNodeWithText("Message").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Retry send")
        compose.runOnIdle { assertEquals(1, repository.sends); repository.failSend = false }
        compose.onNodeWithText("Retry send").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Retry send").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals(2, repository.sends)
            assertEquals(1, repository.messages.value.size)
            assertEquals(1, repository.ids.distinct().size)
        }
    }

    @Test fun unavailableConversationShowsRetryWithoutAComposer() {
        repository.available = false
        open()
        compose.runOnIdle { navigation.navigate(chatRoute("thread")) }
        waitFor("Could not open this private conversation. Check access or Retry.")
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.runOnIdle { repository.available = true }
        compose.onNodeWithText("Retry").performClick()
        waitFor("Message")
    }

    @Test fun discardAfterSendFailureUnlocksComposerAndDoesNotRemoveADeliveredMessage() {
        open()
        compose.runOnIdle { navigation.navigate(chatRoute("thread")) }
        waitFor("Message")
        repository.failSend = true
        compose.onNodeWithText("Message").performTextInput("Original")
        compose.onNodeWithText("Send").performClick()
        waitFor("Discard draft")
        compose.onNodeWithText("Discard draft").performClick()
        compose.onNodeWithText("Message").assertIsEnabled()
        compose.runOnIdle { assertEquals("Original", repository.messages.value.single().text); repository.failSend = false }
        compose.onNodeWithText("Message").performTextInput("New message")
        compose.onNodeWithText("Send").performClick()
        waitFor("New message")
        compose.runOnIdle { assertEquals(2, repository.messages.value.size); assertEquals(2, repository.ids.distinct().size) }
    }

    /** The owner's one campsite, booked by alice (who has messaged) and bob (who has not). */
    private val owners = object : OwnerCampsiteRepository {
        override suspend fun getMyCampsites() = if (repository.uid == site.ownerId) listOf(site) else emptyList()
        override suspend fun getMyCampsite(campsiteId: String) = site.takeIf { repository.uid == it.ownerId && it.id == campsiteId }
        override suspend fun getMyLotteries() = emptyList<Lottery>()
        override suspend fun getPhotos(photoIds: List<String>) = emptyList<Photo>()
        override suspend fun saveCampsite(campsite: Campsite, coverJpegBase64: String?): String = error("Unexpected save")
    }
    private val bookings = object : OwnerReservationRepository {
        override suspend fun getCampsiteReservations(campsiteId: String) = listOf("alice", "bob").mapIndexed { i, entrant ->
            Reservation("r$i", "lottery", "offer", site.id, entrant, site.ownerId, Timestamp.now(), Timestamp.now(), 1, 5000, true,
                Reservation.STATUS_CONFIRMED, createdAt = Timestamp(i.toLong(), 0))
        }
        override suspend fun acceptReservation(reservationId: String): Unit = error("Unexpected accept")
        override suspend fun rejectReservation(reservationId: String, reason: String?): Unit = error("Unexpected reject")
    }

    /** Starts the shared router with fake profile, campsite, lottery, and private-chat services. */
    private fun open(asOwner: Boolean = false, dark: Boolean = false) {
        repository.uid = if (asOwner) "owner" else "alice"
        lateinit var account: OnboardingController
        compose.runOnUiThread { account = OnboardingController(object : UserRepository { override suspend fun initializeUser() = UserProfile(repository.uid) }) }
        val campsites = object : CampsiteRepository { override fun observeCampsites() = flowOf(listOf(site)) }
        val details = object : CampsiteDetailsRepository {
            override suspend fun getCampsite(campsiteId: String) = site
            override suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?) = emptyList<Photo>()
        }
        val profiles = object : ProfileRepository {
            override suspend fun getPhoto(): Photo? = null
            override suspend fun setPhoto(photoId: String, jpegBase64: String, previousPhotoId: String?): Photo = error("Unexpected photo save")
            override suspend fun removePhoto(photoId: String): Unit = error("Unexpected removal")
            override suspend fun getProfile() = UserProfile(repository.uid, "Alex")
            override suspend fun updateContact(name: String, email: String, phone: String): Unit = error("Unexpected edit")
            override suspend fun setNotificationsEnabled(enabled: Boolean): Unit = error("Unexpected preference")
            override suspend fun deleteProfile(): Unit = error("Unexpected deletion")
        }
        val waitlists = object : WaitlistRepository {
            override suspend fun getLotteriesForCampsite(campsiteId: String) = emptyList<LotteryEntry>()
            override suspend fun getMyEntries() = emptyList<LotteryEntry>()
            override suspend fun join(lotteryId: String): Unit = error("Unexpected join")
            override suspend fun leave(lotteryId: String): Unit = error("Unexpected leave")
        }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            navigation = rememberNavController()
            LegioneventsTheme(darkTheme = dark) {
                AppNavigation(account, navigation, campsites = campsites, details = details, profiles = profiles, waitlists = waitlists,
                    messages = repository, owners = owners, ownerReservations = bookings)
            }
        }
        waitFor("Explore campsites")
    }

    /** Waits for a listener or controller result to be rendered. */
    private fun waitFor(text: String) { compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }

    /** Simulates one thread shared by an owner and entrant, including a commit with a lost response. */
    private inner class FakeMessages : MessageRepository {
        var uid = "alice"; var starts = 0; var sends = 0; var listeners = 0
        var available = true; var failSend = false
        val ids = mutableListOf<String>()
        val messages = MutableStateFlow(emptyList<ChatMessage>())
        private fun summary() = ConversationSummary(Conversation("thread", site.id, "alice", "owner",
            messages.value.lastOrNull()?.text ?: "", null, null), "Pine Ridge", uid)
        override fun observeConversations() = messages.map { listOf(summary()) }
        override suspend fun startConversation(campsiteId: String): String { starts++; assertEquals(site.id, campsiteId); return "thread" }
        override suspend fun startPeerToPeerConversation(otherUserId: String): String = error("Unexpected peer start")
        override suspend fun getConversation(conversationId: String) = summary().takeIf { available }
        override suspend fun getPastContacts(userId: String) = pastContacts(listOf(summary().conversation), userId)
        override fun observeMessages(conversationId: String) = messages.onStart { listeners++ }.onCompletion { listeners-- }
        override suspend fun send(conversationId: String, messageId: String, text: String) {
            sends++; ids += messageId
            if (messages.value.none { it.id == messageId }) messages.value += ChatMessage(messageId, "thread", uid,
                summary().conversation.receiverFor(uid), text, false, Timestamp.now(), Timestamp.now())
            if (failSend) error("Response lost after commit")
        }
        /** Adds a peer message to the same live stream without a second UI instance. */
        fun reply(text: String) { messages.value += ChatMessage("reply", "thread", "owner", "alice", text, false, Timestamp.now(), Timestamp.now()) }
    }
}
