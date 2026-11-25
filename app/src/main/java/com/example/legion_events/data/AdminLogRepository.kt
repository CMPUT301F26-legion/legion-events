/*
 * AdminLogRepository.kt
 *
 * Purpose: Contract for the S20 admin logs (US 03.08.01, 03.12.01).
 * Role: Data layer in MVC; AdminLogsController reads every logged notification and
 *   owner-to-entrant message through it.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.AdminLogEntry

/** Reads every notification and owner-to-entrant message. Admins only. */
interface AdminLogRepository {
    /**
     * Lists every notification the app has sent (US 03.08.01).
     * @return Notification entries, in any order.
     */
    suspend fun getNotifications(): List<AdminLogEntry>

    /**
     * Lists every message an owner has sent to entrants: notices and direct messages (US 03.12.01).
     * @return Message entries, in any order.
     */
    suspend fun getOwnerMessages(): List<AdminLogEntry>
}
