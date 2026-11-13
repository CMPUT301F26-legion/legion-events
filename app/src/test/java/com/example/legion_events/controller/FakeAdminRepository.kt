/*
 * FakeAdminRepository.kt
 *
 * Purpose: In-memory AdminRepository for the S19 controller tests.
 * Role: Test double; keeps campsites and profiles in lists and applies removals to them.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import com.example.legion_events.data.AdminRepository
import com.example.legion_events.data.ModeratedCampsite
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.UserProfile

/**
 * Stores campsites and profiles in memory and records removals.
 * @property campsites Every campsite, changed by removals.
 * @property profiles Every profile, changed by removals.
 */
class FakeAdminRepository(
    var campsites: List<ModeratedCampsite> = listOf(
        ModeratedCampsite(Campsite("lake", "jane", "Two Jack Lakeside", "Banff", 6, 3800), true),
        ModeratedCampsite(Campsite("pine", "jane", "Pine Ridge", "Jasper", 4, 2900), true),
        ModeratedCampsite(Campsite("old", "john", "Old Meadow", "Canmore", 2, 2000), false),
    ),
    var profiles: List<UserProfile> = listOf(
        UserProfile("jane", name = "Jane Doe", email = "jane@example.com"),
        UserProfile("john", name = "John Roe"),
        UserProfile("jill", name = "Jill Poe", phone = "780-555-0100"),
        UserProfile("boss", name = "Admin Ann", role = "admin"),
    ),
) : AdminRepository {
    /** When set, every call throws it. */
    var failure: Exception? = null

    override suspend fun getCampsites() = run { failure?.let { throw it }; campsites }
    override suspend fun getCampsite(campsiteId: String) = run { failure?.let { throw it }; campsites.firstOrNull { it.campsite.id == campsiteId } }
    override suspend fun getProfiles() = run { failure?.let { throw it }; profiles }
    override suspend fun getProfile(userId: String) = run { failure?.let { throw it }; profiles.firstOrNull { it.uid == userId } }

    override suspend fun removeCampsite(campsiteId: String) {
        failure?.let { throw it }
        campsites = campsites.map { if (it.campsite.id == campsiteId) it.copy(active = false) else it }
    }

    override suspend fun removeProfile(userId: String) {
        failure?.let { throw it }
        profiles = profiles.map { if (it.uid == userId) it.copy(disabled = true) else it }
    }

    override suspend fun removeOwner(userId: String): Int {
        removeProfile(userId)
        val owned = campsites.filter { it.active && it.campsite.ownerId == userId }
        owned.forEach { removeCampsite(it.campsite.id) }
        return owned.size
    }
}
