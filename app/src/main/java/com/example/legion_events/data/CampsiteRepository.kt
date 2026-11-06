/*
 * CampsiteRepository.kt
 *
 * Purpose: Contract for streaming active campsite listings for search (US 01.01.01).
 * Role: Data layer in MVC; FirebaseRepository implements it.
 * Outstanding issues: None.
 */
package com.example.legion_events.data

import com.example.legion_events.model.Campsite
import kotlinx.coroutines.flow.Flow

/** Provides the active listings used by campsite search. */
interface CampsiteRepository {
    /** Watches active campsites until the controller stops collecting. */
    fun observeCampsites(): Flow<List<Campsite>>
}
