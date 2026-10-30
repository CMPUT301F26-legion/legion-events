package com.example.legion_events.data

import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Photo

/** Reads a selected campsite and its photos without changing listings or lottery entries. */
interface CampsiteDetailsRepository {
    /** Returns the active campsite with this ID, or null if it was removed or made inactive. */
    suspend fun getCampsite(campsiteId: String): Campsite?

    /** Returns campsite photos, placing the cover photo first when it belongs to this campsite. */
    suspend fun getCampsitePhotos(campsiteId: String, coverPhotoId: String?): List<Photo>
}
