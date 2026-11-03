
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Form fields on S15 that can show an error. */
enum class LotteryField { TITLE, CAMPSITES, REGISTRATION, BOOKING, WINNERS, CAPACITY }

/**
 * What the lottery create/edit screen (S15) displays. Dates are `YYYY-MM-DD` strings, or empty if not chosen.
 * @property loading Whether campsites or the existing lottery are still loading.
 * @property loadError Why loading failed, or null.
 * @property editing True when editing an existing lottery, false when creating one.
 * @property locked True when winners were already drawn, so the lottery can no longer be edited.
 * @property campsites The owner's campsites to choose from.
 * @property title The title field's text.
 * @property selectedCampsiteIds Campsites included in this lottery.
 * @property registrationStart First day entrants can join.
 * @property registrationEnd Last day entrants can join (inclusive).
 * @property bookingStart First night winners can book.
 * @property bookingEnd Last night winners can book.
 * @property numWinners The number-of-winners field's text.
 * @property waitlistCapacity The capacity field's text; empty means no limit.
 * @property errors Why each invalid field cannot be saved.
 * @property saving Whether a save is in progress.
 * @property message A short result or failure to show, or null.
 * @property savedLotteryId The saved lottery's ID once saving succeeds, so the screen can open S16.
 */
data class LotteryEditState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val editing: Boolean = false,
    val locked: Boolean = false,
    val campsites: List<Campsite> = emptyList(),
    val title: String = "",
    val selectedCampsiteIds: Set<String> = emptySet(),
    val registrationStart: String = "",
    val registrationEnd: String = "",
    val bookingStart: String = "",
    val bookingEnd: String = "",
    val numWinners: String = "",
    val waitlistCapacity: String = "",
    val errors: Map<LotteryField, String> = emptyMap(),
    val saving: Boolean = false,
    val message: String? = null,
    val savedLotteryId: String? = null,
)

/**
 * Creates or edits a lottery (US 02.04.01–02.04.07).
 *
 * Registration is stored as instants: it opens at the start of [LotteryEditState.registrationStart]
 * and closes at the start of the day after [LotteryEditState.registrationEnd], in [zone].
 *
 * @property lotteryId The lottery to edit, or null to create a new one.
 * @property repository Reads and saves lotteries.
 * @property loadCampsites Loads the signed-in owner's campsites.
 * @property zone Time zone used to turn chosen days into instants.
 */
class LotteryEditController(
    private val lotteryId: String?,
    private val repository: LotteryRepository,
    private val loadCampsites: suspend () -> List<Campsite>,
    private val zone: TimeZone = TimeZone.getDefault(),
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(LotteryEditState(editing = lotteryId != null))
    /** Read-only state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** The lottery as last loaded, used to keep fields the owner cannot edit. */
    private var loaded: Lottery? = null
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
                loaded = lottery
                if (lottery == null) {
                    LotteryEditState(loading = false, campsites = campsites)
                } else {
                    LotteryEditState(
                        loading = false,
                        editing = true,
                        locked = lottery.drawn,
                        message = if (lottery.drawn) "Winners have been drawn. This lottery can no longer be edited." else null,
                        campsites = campsites,
                        title = lottery.title,
                        selectedCampsiteIds = lottery.campsiteIds.toSet(),
                        registrationStart = lottery.registrationStart.toDay(zone),
                        registrationEnd = lottery.registrationEnd.toDay(zone, plusDays = -1),
                        bookingStart = lottery.bookingStart,
                        bookingEnd = lottery.bookingEnd,
                        numWinners = lottery.numWinners.toString(),
                        waitlistCapacity = lottery.waitlistCapacity?.toString().orEmpty(),
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

    fun setTitle(value: String) = edit(LotteryField.TITLE) { copy(title = value) }

    /** Adds the campsite if it is not selected, otherwise removes it. */
    fun toggleCampsite(id: String) = edit(LotteryField.CAMPSITES) {
        copy(selectedCampsiteIds = if (id in selectedCampsiteIds) selectedCampsiteIds - id else selectedCampsiteIds + id)
    }

    fun setRegistrationStart(day: String) = edit(LotteryField.REGISTRATION) { copy(registrationStart = day) }
    fun setRegistrationEnd(day: String) = edit(LotteryField.REGISTRATION) { copy(registrationEnd = day) }
    fun setBookingStart(day: String) = edit(LotteryField.BOOKING) { copy(bookingStart = day) }
    fun setBookingEnd(day: String) = edit(LotteryField.BOOKING) { copy(bookingEnd = day) }
    fun setNumWinners(value: String) = edit(LotteryField.WINNERS) { copy(numWinners = value) }
    fun setWaitlistCapacity(value: String) = edit(LotteryField.CAPACITY) { copy(waitlistCapacity = value) }

    /** Validates the form and creates or updates the lottery when it is valid. */
    fun save() {
        val current = state.value
        if (current.loading || current.saving || current.locked || current.savedLotteryId != null) return
        val errors = validateLottery(current)
        if (errors.isNotEmpty()) {
            mutableState.value = current.copy(errors = errors, message = null)
            return
        }
        val lottery = Lottery(
            id = lotteryId.orEmpty(),
            ownerId = loaded?.ownerId.orEmpty(),
            title = current.title.trim(),
            campsiteIds = current.selectedCampsiteIds.sorted(),
            registrationStart = current.registrationStart.toTimestamp(zone),
            registrationEnd = current.registrationEnd.toTimestamp(zone, plusDays = 1),
            bookingStart = current.bookingStart,
            bookingEnd = current.bookingEnd,
            numWinners = current.numWinners.trim().toInt(),
            waitlistCapacity = current.waitlistCapacity.trim().takeIf { it.isNotEmpty() }?.toInt(),
            drawn = loaded?.drawn ?: false,
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
 * Checks the S15 form. Days are compared as `YYYY-MM-DD` strings, which sort in date order.
 * @return An error message for each invalid field; empty when the form can be saved.
 */
internal fun validateLottery(state: LotteryEditState): Map<LotteryField, String> = buildMap {
    if (state.title.isBlank()) put(LotteryField.TITLE, "Enter a title.")
    if (state.selectedCampsiteIds.isEmpty()) put(LotteryField.CAMPSITES, "Choose at least one campsite.")

    val registrationOk = isDay(state.registrationStart) && isDay(state.registrationEnd)
    if (!registrationOk) put(LotteryField.REGISTRATION, "Choose the registration start and end dates.")
    else if (state.registrationEnd < state.registrationStart) put(LotteryField.REGISTRATION, "Registration must end on or after the day it starts.")

    if (!isDay(state.bookingStart) || !isDay(state.bookingEnd)) put(LotteryField.BOOKING, "Choose the first and last bookable nights.")
    else if (state.bookingEnd < state.bookingStart) put(LotteryField.BOOKING, "The last night must be on or after the first night.")
    else if (registrationOk && state.bookingStart <= state.registrationEnd) put(LotteryField.BOOKING, "Booking must start after registration ends.")

    val winners = state.numWinners.trim().toIntOrNull()
    if (winners == null || winners < 1) put(LotteryField.WINNERS, "Enter at least 1 winner.")

    val capacityText = state.waitlistCapacity.trim()
    if (capacityText.isNotEmpty()) {
        val capacity = capacityText.toIntOrNull()
        if (capacity == null || capacity < 1) put(LotteryField.CAPACITY, "Enter a whole number, or leave it empty for no limit.")
        else if (winners != null && capacity < winners) put(LotteryField.CAPACITY, "Capacity cannot be less than the number of winners.")
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


