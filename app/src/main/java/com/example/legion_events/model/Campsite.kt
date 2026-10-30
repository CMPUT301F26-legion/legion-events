package com.example.legion_events.model

import com.google.firebase.firestore.GeoPoint

/**
 * Fields used to display and search a campsite listing.
 * @property id Firestore document ID.
 * @property ownerId ID of the user who owns this listing, not an account role.
 * @property name Campsite name.
 * @property locationName Area or town.
 * @property maxGuests Maximum number of guests.
 * @property pricePerNight Nightly price in integer CAD cents.
 * @property description Searchable campsite description.
 * @property amenities Available facilities.
 * @property tags Search labels.
 * @property location Optional GPS coordinates.
 * @property rules Owner's rules, displayed as separate lines.
 * @property cancellationPolicy Owner's cancellation terms.
 * @property availableNights Owner-listed night dates in YYYY-MM-DD format, not confirmed booking availability.
 * @property photoId Optional cover photo document ID.
 * @property ratingAverage Stored average rating out of five.
 * @property ratingCount Number of ratings behind the average.
 */
data class Campsite(
    val id: String,
    val ownerId: String,
    val name: String,
    val locationName: String,
    val maxGuests: Int,
    val pricePerNight: Long,
    val description: String = "",
    val amenities: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val location: GeoPoint? = null,
    val rules: List<String> = emptyList(),
    val cancellationPolicy: String = "",
    val availableNights: List<String> = emptyList(),
    val photoId: String? = null,
    val ratingAverage: Double = 0.0,
    val ratingCount: Long = 0,
)
