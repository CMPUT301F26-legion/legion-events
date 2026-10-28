package com.example.legion_events.model

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
)
