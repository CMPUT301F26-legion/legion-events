/*
 * FakeAdminLogRepository.kt
 *
 * Purpose: In-memory AdminLogRepository for the S20 tests.
 * Role: Test double; returns fixed notifications and owner messages.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.AdminLogRepository
import com.example.legion_events.model.AdminLogCategory
import com.example.legion_events.model.AdminLogEntry
import com.google.firebase.Timestamp

/** Seconds since the epoch for 2026-11-24 at noon UTC. */
private const val NOON = 1_795_521_600L

/** A notification sent [minutes] after noon UTC on 2026-11-24. */
internal fun notification(id: String, type: String, to: String, minutes: Long, from: String? = null, text: String = "") =
    AdminLogEntry(id, AdminLogCategory.NOTIFICATION, type, from, listOf(to), text, "L1", Timestamp(NOON + minutes * 60, 0))

/** An owner-to-entrant message sent [minutes] after noon UTC on 2026-11-24. */
internal fun ownerMessage(id: String, type: String, from: String, to: List<String>, text: String, minutes: Long) =
    AdminLogEntry(id, AdminLogCategory.MESSAGE, type, from, to, text, null, Timestamp(NOON + minutes * 60, 0))

/**
 * Returns fixed logs.
 * @property notifications What [getNotifications] returns, in any order.
 * @property messages What [getOwnerMessages] returns, in any order.
 */
class FakeAdminLogRepository(
    var notifications: List<AdminLogEntry> = listOf(
        notification("n1", "lottery_lost", "john", minutes = 0),
        notification("n2", "lottery_won", "jane", minutes = 5),
        notification("n3", "reservation_cancelled", "jane", minutes = 30, from = "jill", text = "Your stay was cancelled."),
    ),
    var messages: List<AdminLogEntry> = listOf(
        ownerMessage("m1", AdminLogEntry.TYPE_OWNER_NOTICE, "jill", listOf("jane", "john"), "Bring bear spray.", 10),
        ownerMessage("m2", AdminLogEntry.TYPE_DIRECT_MESSAGE, "jill", listOf("jane"), "Gate code is 1234.", 20),
    ),
) : AdminLogRepository {
    /** When set, every call throws it. */
    var failure: Exception? = null

    override suspend fun getNotifications() = run { failure?.let { throw it }; notifications }
    override suspend fun getOwnerMessages() = run { failure?.let { throw it }; messages }
}
