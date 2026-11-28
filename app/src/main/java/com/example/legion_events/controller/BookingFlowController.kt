/*
 * BookingFlowController.kt
 * Purpose: Coordinate winner selection, policy acceptance, review, and explicit submission.
 * Role: Controller in MVC; owns the saved draft and retry-safe accepted attempt.
 * Outstanding issues: Automatic worker-status tracking and confirmation Inbox routing are PR 4.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.BookingRepository
import com.example.legion_events.model.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Selection and immutable review data rendered by the booking view; no status implies a held night. */
data class BookingFlowState(
    val options: BookingOptions? = null,
    val selection: BookingSelection = BookingSelection(),
    val rulesAccepted: Boolean = false,
    val loading: Boolean = true,
    val reviewing: Boolean = false,
    val submitting: Boolean = false,
    val review: BookingRequest? = null,
    val pendingAttempt: BookingRequest? = null,
    val result: Reservation? = null,
    val error: String? = null,
    val message: String? = null,
    val checkedAt: Timestamp = Timestamp(0, 0),
    val submissionEnabled: Boolean = false,
) {
    /** Current selected listing and provisional occupied nights. */
    val selected: BookingCampsite? get() = options?.campsites?.firstOrNull { it.campsite.id == selection.campsiteId }
    /** All writes and fresh review reads temporarily prevent draft edits. */
    val busy: Boolean get() = loading || reviewing || submitting
    /** A pending/uncertain attempt is immutable until its saved outcome is recovered. */
    val editable: Boolean get() = !busy && result == null && pendingAttempt == null && options?.canAccept(checkedAt) == true
    /** A complete valid selection plus explicit policy acceptance may enter review. */
    val canReview: Boolean get() = editable && rulesAccepted && selection.problem(selected, checkedAt) == null
}

/**
 * Restores a draft for one account/offer, never submits during restoration or reload.
 * [submissionEnabled] is a rollout gate; the backend still enforces every booking permission.
 * [newId] creates one attempt ID only when the user opens a valid review.
 */
class BookingFlowController(
    private val offerId: String,
    private val userId: String,
    private val repository: BookingRepository,
    private val savedState: SavedStateHandle,
    private val submissionEnabled: Boolean,
    private val now: () -> Timestamp = { Timestamp.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(BookingFlowState(submissionEnabled = submissionEnabled))
    /** Read-only view state. */
    val state = mutableState.asStateFlow()
    private var work: Job? = null

    init {
        val scope = "$offerId:$userId"
        if (savedState.get<String>("bookingScope") != scope) {
            savedState.keys().toList().filter { it.startsWith("booking") }.forEach { savedState.remove<Any>(it) }
            savedState["bookingScope"] = scope
        }
        reload()
    }

    /** Reads saved requests before choices, so an existing acceptance cannot become a second request. */
    fun reload() {
        if (state.value.submitting || state.value.reviewing) return
        work?.cancel()
        val attempt = restoredAttempt()
        mutableState.value = BookingFlowState(selection = restoredSelection(), submissionEnabled = submissionEnabled, checkedAt = now())
        work = viewModelScope.launch {
            try {
                val requests = repository.getRequestsForOffer(offerId)
                checkRequests(requests)
                val saved = requests.firstOrNull { it.id == attempt?.id }
                val active = requests.firstOrNull { it.status in listOf(Reservation.STATUS_REQUESTED, Reservation.STATUS_CONFIRMED) }
                if (active != null) {
                    clearAttempt()
                    mutableState.value = state.value.copy(loading = false, result = active)
                } else if (attempt != null && saved == null) {
                    // A missing response is not evidence that the accepted transaction failed.
                    mutableState.value = state.value.copy(loading = false, pendingAttempt = attempt,
                        message = "We couldn't verify your request. Check its status or retry the same request.")
                } else {
                    if (saved != null) clearAttempt()
                    val options = currentOptions()
                    val choice = options.campsites.firstOrNull { it.campsite.id == state.value.selection.campsiteId }
                    val accepted = acceptedTermsMatch(choice?.campsite)
                    if (!accepted) clearAcceptance()
                    mutableState.value = state.value.copy(options = options, loading = false, rulesAccepted = accepted,
                        message = if (saved?.status == Reservation.STATUS_REJECTED ||
                            requests.firstOrNull()?.status == Reservation.STATUS_REJECTED)
                            "Your previous request was not confirmed. Review availability before trying again." else null,
                        checkedAt = now())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { unavailable() }
            catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, pendingAttempt = attempt,
                    error = if (attempt == null) "Could not load booking choices. Check your connection and retry." else null,
                    message = if (attempt != null) "Could not check your request. Retry the same request or check its status." else null)
            }
        }
    }

    /** Chooses one offered site; changing it clears dates and the previous policy acceptance. */
    fun selectCampsite(id: String) {
        if (!canEdit() || state.value.options?.campsites?.none { it.campsite.id == id } != false ||
            state.value.selection.campsiteId == id) return
        change(BookingSelection(campsiteId = id))
    }

    /** Check-in must be an available current/future night; a new check-in requires a new checkout. */
    fun selectCheckIn(millis: Long) {
        val current = state.value
        val choice = current.selected ?: return
        if (!canEdit() || millis !in bookingCheckIns(choice, now())) return
        if (current.selection.checkInMillis == millis) return
        change(current.selection.copy(checkInMillis = millis, checkOutMillis = null))
    }

    /** Checkout may follow the last available night, but never bridge a missing or locked night. */
    fun selectCheckOut(millis: Long) {
        val current = state.value
        val choice = current.selected ?: return
        val start = current.selection.checkInMillis ?: return
        if (!canEdit() || millis !in bookingCheckouts(choice, start) || current.selection.checkOutMillis == millis) return
        change(current.selection.copy(checkOutMillis = millis))
    }

    /** Enforces capacity without silently clamping the entrant's guest selection. */
    fun selectGuests(guests: Int) {
        val current = state.value
        val capacity = current.selected?.campsite?.maxGuests ?: return
        if (!canEdit() || guests !in 1..capacity || guests == current.selection.guests) return
        change(current.selection.copy(guests = guests))
    }

    /** Explicit checkbox action captures exactly the listing policies and price currently displayed. */
    fun acceptRules(accepted: Boolean) {
        val current = state.value
        val campsite = current.selected?.campsite ?: return
        if (!canEdit()) return
        clearAcceptance()
        if (accepted) {
            savedState["bookingAcceptedSite"] = campsite.id
            savedState["bookingAcceptedRules"] = ArrayList(campsite.rules)
            savedState["bookingAcceptedPolicy"] = campsite.cancellationPolicy
            savedState["bookingAcceptedPrice"] = campsite.pricePerNight
        }
        mutableState.value = current.copy(rulesAccepted = accepted, review = null, message = null, checkedAt = now())
    }

    /** Refreshes choices before opening review; changed policies/prices require a new explicit acceptance. */
    fun review() {
        val current = state.value
        if (!current.canReview || !canEdit()) return
        mutableState.value = current.copy(reviewing = true, message = null)
        work = viewModelScope.launch {
            try {
                val options = currentOptions()
                val checked = now()
                val choice = options.campsites.firstOrNull { it.campsite.id == current.selection.campsiteId }
                val problem = current.selection.problem(choice, checked)
                val termsMatch = acceptedTermsMatch(choice?.campsite)
                val message = when {
                    !options.canAccept(checked) -> "This offer is not currently available for booking. Check its deadline."
                    !termsMatch -> "The campsite price or policies changed. Review them and accept again."
                    problem != null -> problem
                    else -> null
                }
                if (message != null) clearAcceptance()
                val request = if (message == null) {
                    val campsite = checkNotNull(choice).campsite
                    BookingRequest(newId(), offerId, campsite.id,
                        Timestamp(java.util.Date(checkNotNull(current.selection.checkInMillis))),
                        Timestamp(java.util.Date(checkNotNull(current.selection.checkOutMillis))), current.selection.guests,
                        checkNotNull(current.selection.total(campsite)), BookingTerms.from(campsite), true).also { it.validateFor(campsite) }
                } else null
                mutableState.value = current.copy(options = options, reviewing = false, review = request,
                    rulesAccepted = message == null, message = message, checkedAt = checked)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { unavailable() }
            catch (_: Exception) {
                mutableState.value = current.copy(reviewing = false, message = "Could not refresh availability. Try reviewing again.")
            }
        }
    }

    /** Back to selection creates no request and never accepts the offer. */
    fun dismissReview() { if (!state.value.submitting) mutableState.value = state.value.copy(review = null) }

    /** Submits only the exact review explicitly confirmed by the entrant, saving its ID before networking. */
    fun confirm() {
        val current = state.value
        val request = current.review ?: return
        if (current.busy || !submissionEnabled) return
        if (current.options?.canAccept(now()) != true ||
            current.selection.problem(current.selected, now()) != null) {
            clearAcceptance()
            mutableState.value = current.copy(review = null, rulesAccepted = false, checkedAt = now(),
                message = "Your offer or dates are no longer available. Refresh the choices before booking.")
            return
        }
        saveAttempt(request)
        submit(request)
    }

    /** An uncertain outcome retries its original ID/details; it cannot create a new choice or auto-submit. */
    fun retrySubmission() {
        if (state.value.busy || !submissionEnabled) return
        state.value.pendingAttempt?.let(::submit)
    }

    /** Returns from a saved rejection to fresh availability and a new policy review. */
    fun chooseAgain() {
        if (state.value.busy || state.value.result?.status != Reservation.STATUS_REJECTED) return
        clearAttempt()
        clearAcceptance()
        mutableState.value = state.value.copy(result = null)
        reload()
    }

    /** Runs one accepted request; uncertain network failures retain immutable retry data. */
    private fun submit(request: BookingRequest) {
        mutableState.value = state.value.copy(submitting = true, review = null, pendingAttempt = request, message = null)
        work = viewModelScope.launch {
            try {
                val result = repository.acceptOffer(request)
                checkRequests(listOf(result))
                check(result.id == request.id) { "Unexpected booking request." }
                clearAttempt()
                mutableState.value = state.value.copy(submitting = false, pendingAttempt = null, result = result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { unavailable() }
            catch (_: IllegalArgumentException) {
                // Repository policy/price validation aborts before its atomic request write.
                clearAttempt()
                clearAcceptance()
                mutableState.value = state.value.copy(submitting = false, pendingAttempt = null, rulesAccepted = false,
                    message = "The booking details changed. Refresh the choices and review them again.")
            } catch (_: Exception) {
                mutableState.value = state.value.copy(submitting = false,
                    message = "We couldn't verify your request. Check its status or retry the same request.")
            }
        }
    }

    /** Reads owned options and verifies the account captured by this navigation entry. */
    private suspend fun currentOptions(): BookingOptions = repository.getBookingOptions(offerId).also {
        if (it.offer.id != offerId || it.offer.userId != userId) throw SecurityException("Booking account changed.")
    }

    /** A repository response for a different account or offer must never enter this screen. */
    private fun checkRequests(requests: List<Reservation>) {
        if (requests.any { it.userId != userId || it.offerId != offerId }) throw SecurityException("Booking account changed.")
    }

    /** Rechecks presentation deadlines at every editing action. */
    private fun canEdit(): Boolean {
        mutableState.value = state.value.copy(checkedAt = now())
        return state.value.editable
    }

    /** Persists only primitive draft fields; all choices require a fresh policy acceptance after editing. */
    private fun change(selection: BookingSelection) {
        savedState["bookingSite"] = selection.campsiteId
        savedState["bookingStart"] = selection.checkInMillis
        savedState["bookingEnd"] = selection.checkOutMillis
        savedState["bookingGuests"] = selection.guests
        clearAcceptance()
        mutableState.value = state.value.copy(selection = selection, rulesAccepted = false, review = null, message = null, checkedAt = now())
    }

    /** Reconstructs an unsubmitted draft without network calls. */
    private fun restoredSelection() = BookingSelection(savedState["bookingSite"], savedState["bookingStart"],
        savedState["bookingEnd"], savedState.get<Int>("bookingGuests") ?: 1)

    /** Acceptances survive recreation only while exactly the same site, policies, and price remain. */
    private fun acceptedTermsMatch(campsite: Campsite?): Boolean = campsite != null &&
        savedState.get<String>("bookingAcceptedSite") == campsite.id &&
        savedState.get<ArrayList<String>>("bookingAcceptedRules") == campsite.rules &&
        savedState.get<String>("bookingAcceptedPolicy") == campsite.cancellationPolicy &&
        savedState.get<Long>("bookingAcceptedPrice") == campsite.pricePerNight

    /** Removes saved policy approval without changing the entrant's draft. */
    private fun clearAcceptance() {
        savedState.keys().toList().filter { it.startsWith("bookingAccepted") }.forEach { savedState.remove<Any>(it) }
    }

    /** Stores one immutable accepted attempt using SavedStateHandle-supported primitives and string lists. */
    private fun saveAttempt(request: BookingRequest) {
        savedState["bookingAttemptId"] = request.id
        savedState["bookingAttemptSite"] = request.campsiteId
        savedState["bookingAttemptStart"] = request.startDate.toDate().time
        savedState["bookingAttemptEnd"] = request.endDate.toDate().time
        savedState["bookingAttemptGuests"] = request.guestCount
        savedState["bookingAttemptTotal"] = request.totalPrice
        savedState["bookingAttemptRules"] = ArrayList(request.terms.rules)
        savedState["bookingAttemptPolicy"] = request.terms.cancellationPolicy
    }

    /** Reads the frozen attempt for explicit retry; it never submits or changes its values. */
    private fun restoredAttempt(): BookingRequest? {
        val id = savedState.get<String>("bookingAttemptId") ?: return null
        return BookingRequest(id, offerId, checkNotNull(savedState["bookingAttemptSite"]),
            Timestamp(java.util.Date(checkNotNull(savedState.get<Long>("bookingAttemptStart")))),
            Timestamp(java.util.Date(checkNotNull(savedState.get<Long>("bookingAttemptEnd")))),
            checkNotNull(savedState["bookingAttemptGuests"]), checkNotNull(savedState["bookingAttemptTotal"]),
            BookingTerms(checkNotNull(savedState.get<ArrayList<String>>("bookingAttemptRules")).toList(),
                checkNotNull(savedState["bookingAttemptPolicy"])), true)
    }

    /** Stops retaining retry data only after a saved outcome or a known validation abort. */
    private fun clearAttempt() {
        savedState.keys().toList().filter { it.startsWith("bookingAttempt") }.forEach { savedState.remove<Any>(it) }
    }

    /** Clears private presentation data after lost ownership or an account change. */
    private fun unavailable() {
        mutableState.value = BookingFlowState(loading = false, submissionEnabled = submissionEnabled,
            error = "This booking offer is unavailable for your account.")
    }
}
