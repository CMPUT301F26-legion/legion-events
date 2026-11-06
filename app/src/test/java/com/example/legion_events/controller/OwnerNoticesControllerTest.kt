/*
 * OwnerNoticesControllerTest.kt
 *
 * Purpose: Unit tests for S18 owner notices and their delivery to the inbox (US 02.10.01, 02.10.02).
 * Role: Tests OwnerNoticesController and InboxController with in-memory repositories.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.NotificationRepository
import com.example.legion_events.data.OwnerNoticeRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.OwnerNotice
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies sending, the sent list, validation, and inbox delivery of owner notices. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerNoticesControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    /** Sending reaches only joined entrants and appears in the sent list (US 02.10.01, 02.10.02). */
    @Test fun sendReachesJoinedEntrantsAndIsListed() = runTest(dispatcher) {
        val notices = FakeNotices(listOf(
            WaitlistEntrant("a", WaitlistEntrant.STATUS_JOINED, null),
            WaitlistEntrant("b", WaitlistEntrant.STATUS_WITHDRAWN, null),
            WaitlistEntrant("c", WaitlistEntrant.STATUS_JOINED, null),
        ))
        val controller = controller(notices)
        runCurrent()
        assertEquals("L1", controller.state.value.selectedLotteryId)
        assertEquals("Two Jack Lakeside", controller.state.value.targets.single().label)
        controller.setMessage("The road to the campsite is closed until Friday.")
        controller.send()
        runCurrent()
        assertEquals("Sent to 2 entrants.", controller.state.value.result)
        assertEquals("", controller.state.value.message)
        val (sent, label) = controller.state.value.sent.single()
        assertEquals(listOf("a", "c"), sent.recipientIds)
        assertEquals("Two Jack Lakeside", label)
    }

    /** An empty notice or an empty waitlist is refused with a reason. */
    @Test fun emptyMessageOrWaitlistIsRefused() = runTest(dispatcher) {
        val notices = FakeNotices(emptyList())
        val controller = controller(notices)
        runCurrent()
        controller.send()
        assertEquals("Write a notice first.", controller.state.value.messageError)
        controller.setMessage("Hello")
        controller.send()
        runCurrent()
        assertEquals("Nobody is on this waitlist yet.", controller.state.value.messageError)
        assertTrue(notices.sent.isEmpty())
    }

    /** The inbox shows notices next to lottery results, and a notice failure keeps the results. */
    @Test fun inboxShowsNoticesAndSurvivesNoticeFailure() = runTest(dispatcher) {
        val received = OwnerNotice("n1", "owner", "L1", "See you soon", listOf("alice"), Timestamp(1, 0))
        val inbox = inbox(object : FakeNotices(emptyList()) {
            override fun observeReceived(userId: String): Flow<List<OwnerNotice>> = flowOf(listOf(received))
        })
        runCurrent()
        assertEquals(listOf(received), (inbox.state.value as InboxState.Loaded).notices)
        val failing = inbox(object : FakeNotices(emptyList()) {
            override fun observeReceived(userId: String): Flow<List<OwnerNotice>> = flow { error("Rules not deployed") }
        }, key = "failing")
        runCurrent()
        assertTrue(failing.state.value is InboxState.Loaded)
    }

    private fun controller(notices: FakeNotices): OwnerNoticesController = ViewModelProvider.create(store,
        viewModelFactory { initializer { OwnerNoticesController(notices.lotteries, { listOf(CAMPSITE) }, notices) } }
    )[OwnerNoticesController::class]

    private fun inbox(notices: OwnerNoticeRepository, key: String = "inbox"): InboxController =
        ViewModelProvider.create(store, viewModelFactory {
            initializer { InboxController("alice", NotificationRepository { MutableStateFlow(emptyList()) }, notices) }
        })[key, InboxController::class]

    /** In-memory notices for one owner with one lottery whose waitlist is [waitlist]. */
    private open class FakeNotices(private val waitlist: List<WaitlistEntrant>) : OwnerNoticeRepository {
        val sent = mutableListOf<OwnerNotice>()
        val lotteries = object : LotteryRepository {
            override suspend fun createLottery(lottery: Lottery): String = error("unused")
            override suspend fun updateLottery(lottery: Lottery): Unit = error("unused")
            override suspend fun getLottery(id: String): Lottery = LOTTERY
            override suspend fun getMyLotteries() = listOf(LOTTERY)
            override suspend fun getWaitlist(lotteryId: String) = waitlist
        }

        override suspend fun sendNotification(lotteryId: String, message: String): Int {
            val recipients = waitlist.filter { it.status == WaitlistEntrant.STATUS_JOINED }.map { it.userId }
            check(recipients.isNotEmpty()) { "Nobody is on this waitlist yet." }
            sent += OwnerNotice("n${sent.size}", "owner", lotteryId, message, recipients, Timestamp(sent.size + 1L, 0))
            return recipients.size
        }

        override suspend fun getSentNotices() = sent.reversed()
        override fun observeReceived(userId: String): Flow<List<OwnerNotice>> = flowOf(emptyList())
    }

    private companion object {
        val CAMPSITE = Campsite(id = "lake", ownerId = "owner", name = "Two Jack Lakeside", locationName = "Banff",
            maxGuests = 6, pricePerNight = 3800)
        val LOTTERY = Lottery(id = "L1", ownerId = "owner", campsiteIds = listOf("lake"),
            registrationStartAt = Timestamp(1, 0), registrationEndAt = Timestamp(2, 0),
            bookingStartAt = Timestamp(3, 0), bookingEndAt = Timestamp(4, 0), winnerCount = 1)
    }
}
