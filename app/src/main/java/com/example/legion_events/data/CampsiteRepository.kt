package com.example.legion_events.data

import com.example.legion_events.model.Campsite
import kotlinx.coroutines.flow.Flow

/** Provides the active listings used by campsite search. */
interface CampsiteRepository {
    /** Watches active campsites until the controller stops collecting. */
    fun observeCampsites(): Flow<List<Campsite>>
}
