/*
 * LotteryEditController.kt
 *
 * Purpose: Holds the S15 create/edit lottery form, validates it, and saves the lottery (US 02.04.01 to 02.04.07).
 * Role: Controller in MVC; LotteryEditScreen renders its state and LotteryRepository saves it.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.MAX_LOTTERY_CAMPSITES
import com.example.legion_events.model.MAX_WAITLIST_LIMIT
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Form fields on S15 that can show an error. */
enum class LotteryField { CAMPSITES, REGISTRATION, BOOKING, WINNERS, LIMIT }

/**
 * What the lottery create/edit screen (S15) displays. Days are `YYYY-MM-DD` strings, or empty if not chosen.
 * @property loading Whether campsites or the existing lottery are still loading.
 * @property loadError Why loading failed, or null.
 * @property editing True when editing an existing lottery, false when creating one.
 * @property locked True when the lottery can no longer be edited; [message] says why.
 * @property campsites The owner's campsites to choose from.
 * @property selectedCampsiteIds Campsites included in this lottery.
 * @property registrationStart First day entrants can join.
 * @property registrationEnd Last day entrants can join (inclusive).
 * @property bookingStart First day winners can book.
 * @property bookingEnd Last day winners can book (inclusive).
 * @property winnerCount The number-of-winners field's text.
 * @property waitlistLimit The waitlist limit field's text; empty saves [MAX_WAITLIST_LIMIT].
 * @property errors Why each invalid field cannot be saved.
 * @property saving Whether a save is in progress.
 * @property message A short result, failure, or lock reason to show, or null.
 * @property savedLotteryId The saved lottery's ID once saving succeeds, so the screen can open S16.
 */
data class LotteryEditState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val editing: Boolean = false,
    val locked: Boolean = false,
    val campsites: List<Campsite> = emptyList(),
    val selectedCampsiteIds: Set<String> = emptySet(),
    val registrationStart: String = "",
    val registrationEnd: String = "",
    val bookingStart: String = "",
    val bookingEnd: String = "",
    val winnerCount: String = "",
    val waitlistLimit: String = "",
    val errors: Map<LotteryField, String> = emptyMap(),
    val saving: Boolean = false,
    val message: String? = null,
    val savedLotteryId: String? = null,
)

/**
 * Creates or edits a lottery (US 02.04.01–02.04.07), applying the same limits as the Firestore rules.
 *
 * Owners pick days. Each period opens at the start of its first day and closes at the start of the
 * day after its last day, in [zone], so the whole last day is included.
 *
 * @property lotteryId The lottery to edit, or null to create a new one.
 * @property repository Reads and saves lotteries.
 * @property loadCampsites Loads the signed-in owner's campsites.
 * @property zone Time zone used to turn chosen days into instants.
 * @property now The current time; replaced in tests.
 */
class LotteryEditController(
    private val lotteryId: String?,
    private val repository: LotteryRepository,
    private val loadCampsites: suspend () -> List<Campsite>,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val now: () -> Date = { Date() },
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(LotteryEditState(editing = lotteryId != null))
    /** Read-only state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** Current load request, cancelled before retrying. */
    private var request: Job? = null

    init { retry() }

    /** Loads the owner's campsites and, when editing, the saved lottery. Discards unsaved edits. */
    fun retry() {
        request?.cancel()
        mutableState.value = LotteryEditState(editing = lotteryId != null)
        request = viewModelScope.launch {
            mutableState.value = try {
                val campsites = loadCampsites()
                val lottery = lotteryId?.let { repository.getLottery(it) }
                if (lottery == null) {
                    LotteryEditState(loading = false, campsites = campsites)
                } else {
                    val lockReason = lockReason(lottery, now())
                    LotteryEditState(
                        loading = false,
                        editing = true,
                        locked = lockReason != null,
                        message = lockReason,
                        campsites = campsites,
                        selectedCampsiteIds = lottery.campsiteIds.toSet(),
                        registrationStart = lottery.registrationStartAt.toDay(zone),
                        registrationEnd = lottery.registrationEndAt.toDay(zone, plusDays = -1),
                        bookingStart = lottery.bookingStartAt.toDay(zone),
                        bookingEnd = lottery.bookingEndAt.toDay(zone, plusDays = -1),
                        winnerCount = lottery.winnerCount.toString(),
                        waitlistLimit = lottery.waitlistLimit.toString(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LotteryEditState(loading = false, editing = lotteryId != null,
                    loadError = "Could not load the lottery. Check your connection and retry.")
            }
        }
    }

    /** Adds the campsite if it is not selected, otherwise removes it. */
    fun toggleCampsite(id: String) = edit(LotteryField.CAMPSITES) {
        copy(selectedCampsiteIds = if (id in selectedCampsiteIds) selectedCampsiteIds - id else selectedCampsiteIds + id)
    }

    fun setRegistrationStart(day: String) = edit(LotteryField.REGISTRATION) { copy(registrationStart = day) }
    fun setRegistrationEnd(day: String) = edit(LotteryField.REGISTRATION) { copy(registrationEnd = day) }
    fun setBookingStart(day: String) = edit(LotteryField.BOOKING) { copy(bookingStart = day) }
    fun setBookingEnd(day: String) = edit(LotteryField.BOOKING) { copy(bookingEnd = day) }
    fun setWinnerCount(value: String) = edit(LotteryField.WINNERS) { copy(winnerCount = value) }
    fun setWaitlistLimit(value: String) = edit(LotteryField.LIMIT) { copy(waitlistLimit = value) }

    /** Validates the form and creates or updates the lottery when it is valid. */
    fun save() {
        val current = state.value
        if (current.loading || current.saving || current.locked || current.savedLotteryId != null) return
        val errors = validateLottery(current, now(), zone)
        if (errors.isNotEmpty()) {
            mutableState.value = current.copy(errors = errors, message = null)
            return
        }
        val lottery = Lottery(
            id = lotteryId.orEmpty(),
            ownerId = "",
            campsiteIds = current.selectedCampsiteIds.sorted(),
            registrationStartAt = current.registrationStart.toTimestamp(zone),
            registrationEndAt = current.registrationEnd.toTimestamp(zone, plusDays = 1),
            bookingStartAt = current.bookingStart.toTimestamp(zone),
            bookingEndAt = current.bookingEnd.toTimestamp(zone, plusDays = 1),
            winnerCount = current.winnerCount.trim().toInt(),
            waitlistLimit = current.waitlistLimit.trim().toIntOrNull() ?: MAX_WAITLIST_LIMIT,
        )
        mutableState.value = current.copy(errors = emptyMap(), saving = true, message = null)
        viewModelScope.launch {
            mutableState.value = try {
                val id = if (lotteryId == null) repository.createLottery(lottery) else {
                    repository.updateLottery(lottery)
                    lotteryId
                }
                state.value.copy(saving = false, savedLotteryId = id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value.copy(saving = false, message = "Could not save the lottery. Check your connection and retry.")
            }
        }
    }

    /** Applies a field change and clears that field's error. Ignored while saving or locked. */
    private fun edit(field: LotteryField, change: LotteryEditState.() -> LotteryEditState) {
        val current = state.value
        if (current.saving || current.locked) return
        mutableState.value = current.change().copy(errors = current.errors - field, message = null)
    }
}

/**
 * Why a saved lottery can no longer be edited, or null if it can. Mirrors the update rule:
 * only before registration opens, while nobody has joined, and before the draw (US 02.04.06).
 */
internal fun lockReason(lottery: Lottery, now: Date): String? = when {
    lottery.winnersDrawn -> "Winners have been drawn. This lottery can no longer be edited."
    lottery.entrantCount > 0 -> "Entrants have already joined. This lottery can no longer be edited."
    !now.before(lottery.registrationStartAt.toDate()) -> "Registration has opened. This lottery can no longer be edited."
    else -> null
}

/**
 * Checks the S15 form against the Firestore rules. Days are compared as `YYYY-MM-DD` strings,
 * which sort in date order.
 * @return An error message for each invalid field; empty when the form can be saved.
 */
internal fun validateLottery(state: LotteryEditState, now: Date, zone: TimeZone): Map<LotteryField, String> = buildMap {
    when {
        state.selectedCampsiteIds.isEmpty() -> put(LotteryField.CAMPSITES, "Choose at least one campsite.")
        state.selectedCampsiteIds.size > MAX_LOTTERY_CAMPSITES ->
            put(LotteryField.CAMPSITES, "Choose at most $MAX_LOTTERY_CAMPSITES campsites.")
    }

    val registrationOk = isDay(state.registrationStart) && isDay(state.registrationEnd)
    when {
        !registrationOk -> put(LotteryField.REGISTRATION, "Choose the first and last registration days.")
        state.registrationEnd < state.registrationStart ->
            put(LotteryField.REGISTRATION, "Registration must end on or after the day it opens.")
        state.editing && !now.before(state.registrationStart.toTimestamp(zone).toDate()) ->
            put(LotteryField.REGISTRATION, "Registration must open after today.")
        !now.before(state.registrationEnd.toTimestamp(zone, plusDays = 1).toDate()) ->
            put(LotteryField.REGISTRATION, "Registration must end today or later.")
    }

    when {
        !isDay(state.bookingStart) || !isDay(state.bookingEnd) ->
            put(LotteryField.BOOKING, "Choose the first and last booking days.")
        state.bookingEnd < state.bookingStart -> put(LotteryField.BOOKING, "Booking must end on or after the day it opens.")
        registrationOk && state.bookingStart <= state.registrationEnd ->
            put(LotteryField.BOOKING, "Booking must open after registration ends.")
    }

    val winners = state.winnerCount.trim().toIntOrNull()
    val limitText = state.waitlistLimit.trim()
    val limit = if (limitText.isEmpty()) MAX_WAITLIST_LIMIT else limitText.toIntOrNull()
    if (limit == null || limit < 1 || limit > MAX_WAITLIST_LIMIT) {
        put(LotteryField.LIMIT, "Enter 1 to $MAX_WAITLIST_LIMIT, or leave it empty for $MAX_WAITLIST_LIMIT.")
    }
    when {
        winners == null || winners < 1 -> put(LotteryField.WINNERS, "Enter at least 1 winner.")
        limit != null && limit in 1..MAX_WAITLIST_LIMIT && winners > limit ->
            put(LotteryField.WINNERS, "Winners cannot be more than the waitlist limit ($limit).")
    }
}

/** Formats and parses `YYYY-MM-DD` days without java.time, which needs API 26 (minSdk is 24). */
private fun dayFormat(zone: TimeZone) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = zone
    isLenient = false
}

/** Whether [value] is a real calendar day such as `2026-07-14`. */
private fun isDay(value: String): Boolean =
    Regex("""\d{4}-\d{2}-\d{2}""").matches(value) &&
        try { dayFormat(TimeZone.getTimeZone("UTC")).parse(value) != null } catch (_: ParseException) { false }

/** The instant this day starts in [zone], moved by [plusDays] whole days. */
private fun String.toTimestamp(zone: TimeZone, plusDays: Int = 0): Timestamp {
    val calendar = Calendar.getInstance(zone)
    calendar.time = checkNotNull(dayFormat(zone).parse(this))
    calendar.add(Calendar.DAY_OF_MONTH, plusDays)
    return Timestamp(calendar.time)
}

/** The `YYYY-MM-DD` day this instant falls on in [zone], moved by [plusDays] whole days. */
private fun Timestamp.toDay(zone: TimeZone, plusDays: Int = 0): String {
    val calendar = Calendar.getInstance(zone)
    calendar.time = toDate()
    calendar.add(Calendar.DAY_OF_MONTH, plusDays)
    return dayFormat(zone).format(calendar.time)
}
