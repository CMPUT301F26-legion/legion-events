/*
 * AdminLogsController.kt
 *
 * Purpose: S20 Admin logs: every notification the app sent (US 03.08.01) and every message an
 *   owner sent to entrants (US 03.12.01), newest first, with sender and recipient names.
 * Role: Controller in MVC; AdminLogsScreen renders its state, AdminLogRepository supplies the
 *   entries, and AdminRepository supplies profile names.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.AdminLogRepository
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.model.AdminLogCategory
import com.example.legion_events.model.AdminLogEntry
import com.example.legion_events.model.UserProfile
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One labelled log row.
 * @property entry The logged notification or message.
 * @property title What kind of entry it is, such as "Lottery won".
 * @property from Who sent it.
 * @property to Who received it.
 * @property time When it was sent.
 */
data class AdminLogRow(val entry: AdminLogEntry, val title: String, val from: String, val to: String, val time: String)

/**
 * What S20 shows.
 * @property loading Whether the logs are loading.
 * @property error Why the logs could not be loaded, or null.
 * @property tab The selected tab.
 * @property notifications Every notification, newest first.
 * @property messages Every owner-to-entrant message, newest first.
 */
data class AdminLogsState(
    val loading: Boolean = true,
    val error: String? = null,
    val tab: AdminLogCategory = AdminLogCategory.NOTIFICATION,
    val notifications: List<AdminLogRow> = emptyList(),
    val messages: List<AdminLogRow> = emptyList(),
)

/**
 * Loads and labels the admin logs.
 * @property logs Reads every notification and owner-to-entrant message.
 * @property admin Reads profiles to name senders and recipients.
 * @property zone Time zone for the displayed times.
 */
class AdminLogsController(
    private val logs: AdminLogRepository,
    private val admin: AdminRepository,
    private val zone: TimeZone = TimeZone.getDefault(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(AdminLogsState())
    /** State collected by S20. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads both logs. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                coroutineScope {
                    val notifications = async { logs.getNotifications() }
                    val messages = async { logs.getOwnerMessages() }
                    val people = async { admin.getProfiles().associateBy { it.uid } }
                    val names = people.await()
                    mutableState.value = state.value.copy(
                        loading = false,
                        notifications = rows(notifications.await(), names),
                        messages = rows(messages.await(), names),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = "Could not load the logs. Please retry.")
            }
        }
    }

    /**
     * Switches between the notification and message logs.
     * @param tab The tab to show.
     */
    fun selectTab(tab: AdminLogCategory) {
        mutableState.value = state.value.copy(tab = tab)
    }

    /** Labels entries and sorts them newest first; entries without a time go last. */
    private fun rows(entries: List<AdminLogEntry>, people: Map<String, UserProfile>): List<AdminLogRow> =
        entries.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }.map { entry ->
            AdminLogRow(
                entry = entry,
                title = logTitle(entry.type),
                from = entry.fromUserId?.let { name(people[it]) } ?: "CampLot",
                to = recipients(entry.toUserIds, people),
                time = entry.createdAt?.let { logTime(it, zone) } ?: "Time unknown",
            )
        }
}

/** A readable label for a stored notification or message type. */
internal fun logTitle(type: String): String = when (type) {
    "lottery_won" -> "Lottery won"
    "lottery_lost" -> "Lottery not won"
    "reservation_cancelled" -> "Reservation cancelled"
    AdminLogEntry.TYPE_OWNER_NOTICE -> "Notice to entrants"
    AdminLogEntry.TYPE_DIRECT_MESSAGE -> "Message"
    "" -> "Notification"
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** One recipient's name, or how many recipients there were. */
private fun recipients(ids: List<String>, people: Map<String, UserProfile>): String = when (ids.size) {
    0 -> "No recipients"
    1 -> name(people[ids.single()])
    else -> "${ids.size} entrants"
}

/** A profile's name, or a placeholder for a missing or unnamed profile. */
private fun name(profile: UserProfile?): String = profile?.let(::displayName) ?: "Unknown user"

/** Formats a log time such as "Nov 24, 2026, 6:57 PM". */
internal fun logTime(time: Timestamp, zone: TimeZone): String =
    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).apply { timeZone = zone }.format(time.toDate())
