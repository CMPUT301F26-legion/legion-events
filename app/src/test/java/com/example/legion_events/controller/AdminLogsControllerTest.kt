/*
 * AdminLogsControllerTest.kt
 *
 * Purpose: Unit tests for S20 Admin logs (US 03.08.01, 03.12.01).
 * Role: Tests AdminLogsController with in-memory log and admin repositories.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.model.AdminLogCategory
import com.example.legion_events.model.AdminLogEntry
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** Verifies the admin notification and message logs. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminLogsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val regina = TimeZone.getTimeZone("America/Regina")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun kotlinx.coroutines.test.TestScope.controller(logs: FakeAdminLogRepository = FakeAdminLogRepository()) =
        AdminLogsController(logs, FakeAdminRepository(), regina).also { runCurrent() }

    /** Every notification is listed newest first with its type, sender, and recipient (US 03.08.01). */
    @Test fun listsEveryNotificationNewestFirst() = runTest(dispatcher) {
        val rows = controller().state.value.notifications
        assertEquals(listOf("n3", "n2", "n1"), rows.map { it.entry.id })
        assertEquals(listOf("Reservation cancelled", "Lottery won", "Lottery not won"), rows.map { it.title })
        assertEquals(listOf("Jill Poe", "CampLot", "CampLot"), rows.map { it.from })
        assertEquals(listOf("Jane Doe", "Jane Doe", "John Roe"), rows.map { it.to })
    }

    /** Owner notices and direct messages are listed together, newest first (US 03.12.01). */
    @Test fun listsEveryOwnerMessageNewestFirst() = runTest(dispatcher) {
        val rows = controller().state.value.messages
        assertEquals(listOf("m2", "m1"), rows.map { it.entry.id })
        assertEquals(listOf("Message", "Notice to entrants"), rows.map { it.title })
        assertEquals(listOf("Jill Poe", "Jill Poe"), rows.map { it.from })
        assertEquals(listOf("Jane Doe", "2 entrants"), rows.map { it.to })
        assertEquals("Gate code is 1234.", rows.first().entry.text)
    }

    /** Times are shown in the device's time zone. */
    @Test fun formatsTimesInTheDeviceZone() = runTest(dispatcher) {
        assertEquals("Nov 24, 2026, 6:30 AM", controller().state.value.notifications.first().time)
    }

    /** The Notifications tab opens first, and the admin can switch to Messages. */
    @Test fun switchesTabs() = runTest(dispatcher) {
        val controller = controller()
        assertEquals(AdminLogCategory.NOTIFICATION, controller.state.value.tab)
        controller.selectTab(AdminLogCategory.MESSAGE)
        assertEquals(AdminLogCategory.MESSAGE, controller.state.value.tab)
    }

    /** Unknown users, missing times, and unknown types still produce readable rows. */
    @Test fun labelsMissingData() = runTest(dispatcher) {
        val logs = FakeAdminLogRepository(
            notifications = listOf(
                AdminLogEntry("x", AdminLogCategory.NOTIFICATION, "booking_reminder", "ghost", listOf("nobody"), "", null, null),
            ),
            messages = listOf(
                AdminLogEntry("y", AdminLogCategory.MESSAGE, AdminLogEntry.TYPE_OWNER_NOTICE, "jill", emptyList(), "Hi", null, Timestamp(0, 0)),
            ),
        )
        val state = controller(logs).state.value
        with(state.notifications.single()) {
            assertEquals("Booking reminder", title)
            assertEquals("Unknown user", from)
            assertEquals("Unknown user", to)
            assertEquals("Time unknown", time)
        }
        assertEquals("No recipients", state.messages.single().to)
    }

    /** A failed load shows an error, and Retry recovers. */
    @Test fun failureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val logs = FakeAdminLogRepository().apply { failure = IllegalStateException("offline") }
        val controller = controller(logs)
        assertEquals("Could not load the logs. Please retry.", controller.state.value.error)
        assertFalse(controller.state.value.loading)
        logs.failure = null
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.error)
        assertEquals(3, controller.state.value.notifications.size)
    }

    /** Empty logs load without an error. */
    @Test fun emptyLogsLoad() = runTest(dispatcher) {
        val state = controller(FakeAdminLogRepository(emptyList(), emptyList())).state.value
        assertTrue(state.notifications.isEmpty())
        assertTrue(state.messages.isEmpty())
        assertNull(state.error)
        assertFalse(state.loading)
    }
}
