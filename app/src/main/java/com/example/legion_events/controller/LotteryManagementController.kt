/*
 * LotteryManagementController.kt
 *
 * Purpose: Loads one lottery, its waiting list, and its winners for S16 (US 02.04.06, 02.05.01, 02.09.01).
 * Role: Controller in MVC; LotteryManagementScreen renders its state and LotteryRepository supplies the data.
 * Outstanding issues: Entrants are shown by position, because owners cannot read entrant profiles;
 * winners' booking status needs the offers data.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.WaitlistEntrant
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

/** The two lists on S16. */
enum class ManagementTab { WAITLIST, WINNERS }

/** Waitlist status the draw gives entrants it picks. */
internal const val STATUS_WON = "won"
/** Waitlist status the draw gives entrants it does not pick. */
internal const val STATUS_LOST = "lost"

/**
 * One row of an S16 list.
 * @property position 1-based place within its list.
 * @property label What to call the entrant; the same entrant keeps the same label in both lists.
 * @property joinedAt When they joined, or null if unknown.
 */
data class WaitlistRow(val position: Int, val label: String, val joinedAt: Timestamp?)

/**
 * Turns waitlist entries into the rows of one S16 list, earliest joiner first.
 * Entrants are numbered by joining order among everyone who did not withdraw, so "Entrant 3"
 * means the same person on the waiting list and the winners list.
 * Owners cannot read entrants' profiles under the current Firestore rules, so rows are numbered
 * instead of named. If entries later store a display name, change [label] here only.
 * @param entries Every entry of one lottery.
 * @param status The status to list: `joined` before the draw, then `won` or `lost`.
 * @return The matching rows in joining order.
 */
internal fun waitlistRows(entries: List<WaitlistEntrant>, status: String): List<WaitlistRow> {
    val entrants = entries.filter { it.status != WaitlistEntrant.STATUS_WITHDRAWN }
        .sortedWith(compareBy(nullsLast<Timestamp>()) { it.joinedAt })
    return entrants.withIndex().filter { it.value.status == status }
        .mapIndexed { position, (order, entry) -> WaitlistRow(position + 1, label(order), entry.joinedAt) }
}

/** The display name of the entrant at [index] in joining order. */
private fun label(index: Int) = "Entrant ${index + 1}"

/**
 * What S16 lottery management displays.
 * @property loading Whether the lottery is still loading.
 * @property error Why the lottery could not be loaded, or null.
 * @property lottery The lottery once loaded.
 * @property title Its display name, from its campsites.
 * @property badge Its state right now.
 * @property editBlockedReason Why Edit is unavailable, or null when the owner can edit (US 02.04.06).
 * @property waitlist Entrants still waiting before the draw, or those not picked after it (US 02.09.01).
 * @property winners Entrants the draw picked; empty before the draw (US 02.05.01).
 * @property waitlistError Why the entries could not be loaded, or null.
 * @property tab The list being shown.
 */
data class LotteryManagementState(
    val loading: Boolean = true,
    val error: String? = null,
    val lottery: Lottery? = null,
    val title: String = "",
    val badge: LotteryBadge? = null,
    val editBlockedReason: String? = null,
    val waitlist: List<WaitlistRow> = emptyList(),
    val winners: List<WaitlistRow> = emptyList(),
    val waitlistError: String? = null,
    val tab: ManagementTab = ManagementTab.WAITLIST,
)

/**
 * Loads one lottery and its waiting list for S16.
 * @property lotteryId The lottery to manage.
 * @property repository Reads the lottery and its waitlist.
 * @property loadCampsites Loads the owner's campsites, used only for the display name.
 * @property now The current time; replaced in tests.
 */
class LotteryManagementController(
    private val lotteryId: String,
    private val repository: LotteryRepository,
    private val loadCampsites: suspend () -> List<Campsite>,
    private val now: () -> Date = { Date() },
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(LotteryManagementState())
    /** Read-only state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** Current load request, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads the lottery and waiting list, for example after returning from S15. */
    fun refresh() {
        request?.cancel()
        val tab = state.value.tab
        mutableState.value = state.value.copy(loading = state.value.lottery == null, error = null)
        request = viewModelScope.launch {
            mutableState.value = try {
                val lottery = repository.getLottery(lotteryId)
                val names = try {
                    loadCampsites().associate { it.id to it.name }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyMap()
                }
                // A waitlist failure still shows the lottery itself.
                val entries = try {
                    Result.success(repository.getWaitlist(lotteryId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                val time = now()
                val all = entries.getOrDefault(emptyList())
                // After the draw nobody is "joined" any more: the waiting list shows who was not picked.
                val waiting = if (lottery.winnersDrawn) STATUS_LOST else WaitlistEntrant.STATUS_JOINED
                LotteryManagementState(
                    loading = false,
                    lottery = lottery,
                    title = lotteryTitle(lottery, names),
                    badge = lotteryBadge(lottery, time.time),
                    editBlockedReason = lockReason(lottery, time),
                    waitlist = waitlistRows(all, waiting),
                    winners = waitlistRows(all, STATUS_WON),
                    waitlistError = if (entries.isFailure) "Could not load the waiting list. Tap Retry to try again." else null,
                    tab = tab,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value.copy(loading = false, error = "Could not load this lottery. Check your connection and retry.")
            }
        }
    }

    /** Shows the waiting list or the winners. */
    fun selectTab(tab: ManagementTab) {
        mutableState.value = state.value.copy(tab = tab)
    }
}
