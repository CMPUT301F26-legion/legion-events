/*
 * BookingOfferController.kt
 *
 * Purpose: Load an owned offer and confirm declines for US 01.04.03.
 * Role: Controller in MVC; supplies detail state through OfferRepository.
 * Outstanding issues: Offer acceptance and reservation creation are separate work.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.OfferRepository
import com.example.legion_events.model.BookingOffer
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for one owned offer and its decline confirmation.
 * @property offer Loaded offer, or null when unavailable.
 * @property loading Whether the initial read or a reload is pending.
 * @property declining Whether a decline request is in flight.
 * @property confirming Whether to show the confirmation dialog.
 * @property expired Whether the response deadline had passed at the latest check.
 * @property error Read failure that prevents showing an offer.
 * @property actionError Decline failure shown alongside the last loaded offer.
 */
data class BookingOfferState(
    val offer: BookingOffer? = null,
    val loading: Boolean = true,
    val declining: Boolean = false,
    val confirming: Boolean = false,
    val expired: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    /** Whether the loaded offer can currently begin a decline interaction. */
    val canDecline: Boolean
        get() = !loading && !declining && !expired && offer?.status == BookingOffer.STATUS_OPEN
}

/**
 * Reads an offer and confirms declines without allowing duplicate in-flight actions.
 * @property offerId Document ID supplied by navigation, never a user-supplied owner UID.
 * @property repository Provides owned reads and atomic, retry-safe declines.
 * @property now Device clock for presentation; the repository and server rules enforce validity.
 */
class BookingOfferController(
    private val offerId: String,
    private val repository: OfferRepository,
    private val now: () -> Timestamp = { Timestamp.now() },
) : ViewModel() {
    /** Mutable state owned by the controller. */
    private val mutableState = MutableStateFlow(BookingOfferState())
    /** State collected by the detail view. */
    val state = mutableState.asStateFlow()
    /** Read request cancelled when the user explicitly reloads or leaves the screen. */
    private var request: Job? = null

    init { retry() }

    /** Reloads the offer; an in-flight decline must finish before a read can replace its state. */
    fun retry() {
        if (state.value.declining) return
        request?.cancel()
        mutableState.value = BookingOfferState()
        request = viewModelScope.launch {
            try {
                val offer = repository.getOffer(offerId)
                mutableState.value = BookingOfferState(offer = offer, loading = false, expired = isExpired(offer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                mutableState.value = BookingOfferState(loading = false, error = "This offer is unavailable for your account.")
            } catch (_: Exception) {
                mutableState.value = BookingOfferState(loading = false,
                    error = "Could not load this offer. Check your connection and retry.")
            }
        }
    }

    /** Opens confirmation only for an open offer whose deadline has not passed. */
    fun requestDecline() {
        if (!state.value.canDecline || expireBeforeAction()) return
        mutableState.value = state.value.copy(confirming = true, actionError = null)
    }

    /** Dismisses confirmation without changing any saved offer. */
    fun cancelDecline() {
        mutableState.value = state.value.copy(confirming = false)
    }

    /**
     * Submits a confirmed decline once and displays the committed repository response.
     * An uncertain network outcome can be retried safely using the same offer ID.
     */
    fun confirmDecline() {
        if (!state.value.confirming || !state.value.canDecline || expireBeforeAction()) return
        mutableState.value = state.value.copy(declining = true, confirming = false, actionError = null)
        viewModelScope.launch {
            try {
                val saved = repository.declineOffer(offerId)
                mutableState.value = BookingOfferState(offer = saved, loading = false, expired = isExpired(saved))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                mutableState.value = BookingOfferState(loading = false, error = "This offer is unavailable for your account.")
            } catch (_: Exception) {
                mutableState.value = state.value.copy(declining = false,
                    actionError = "Could not confirm the decline. Reload the offer or try declining again.")
            }
        }
    }

    /** Returns true when a response deadline has passed; terminal statuses retain their own labels. */
    private fun isExpired(offer: BookingOffer?): Boolean =
        offer?.status == BookingOffer.STATUS_OPEN && offer.expiresAt?.let { it <= now() } == true

    /** Refreshes deadline state at each action, including when a confirmation dialog was left open. */
    private fun expireBeforeAction(): Boolean {
        if (!isExpired(state.value.offer)) return false
        mutableState.value = state.value.copy(expired = true, confirming = false,
            actionError = "This offer has expired. Reload to see its current status.")
        return true
    }
}
