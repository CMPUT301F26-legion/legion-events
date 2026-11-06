/*
 * BookingOffersController.kt
 *
 * Purpose: Load and refresh the signed-in entrant's lottery offers.
 * Role: Controller in MVC; supplies list state through OfferRepository.
 * Outstanding issues: Part 5's inbox will provide another entry point to offer details.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.OfferRepository
import com.example.legion_events.model.BookingOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the entrant's offer list.
 * @property offers Owned offers, including previously declined, used, or expired offers.
 * @property loading Whether a list read is in progress.
 * @property error Safe, user-facing read error, or null.
 */
data class BookingOffersState(
    val offers: List<BookingOffer> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Loads offers for one navigation entry and reloads after returning from a decline.
 * @property repository Resolves the authenticated UID and enforces ownership.
 */
class BookingOffersController(private val repository: OfferRepository) : ViewModel() {
    /** Mutable state owned by the controller. */
    private val mutableState = MutableStateFlow(BookingOffersState())
    /** Lifecycle-aware state consumed by the list view. */
    val state = mutableState.asStateFlow()
    /** Previous request, cancelled before a replacement read. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the authenticated user's list and cancels an older in-flight read. */
    fun retry() {
        request?.cancel()
        mutableState.value = BookingOffersState()
        request = viewModelScope.launch {
            try {
                val offers = repository.getOffersForCurrentUser().sortedByDescending { it.createdAt }
                mutableState.value = BookingOffersState(offers = offers, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = BookingOffersState(loading = false,
                    error = "Could not load your offers. Check your connection and retry.")
            }
        }
    }
}
