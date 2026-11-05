package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Names a lottery by its campsites, because the Firestore rules do not allow a lottery title.
 * @param lottery The lottery.
 * @param campsiteNames Campsite names by ID; IDs without a name are skipped.
 * @return Such as `Two Jack Lakeside`, `Two Jack Lakeside + 2 more`, or `Lottery` when no name is known.
 */
internal fun lotteryTitle(lottery: Lottery, campsiteNames: Map<String, String>): String {
    val names = lottery.campsiteIds.mapNotNull { campsiteNames[it]?.takeIf(String::isNotBlank) }
    return when {
        names.isEmpty() -> "Lottery"
        lottery.campsiteIds.size == 1 -> names.first()
        else -> "${names.first()} + ${lottery.campsiteIds.size - 1} more"
    }
}

/**
 * One card on the owner's Lotteries tab.
 * @property lottery The lottery.
 * @property title Its display name, from its campsites.
 * @property badge Its state right now.
 */
data class OwnerLotteryItem(val lottery: Lottery, val title: String, val badge: LotteryBadge)

/**
 * What the owner's Lotteries tab displays.
 * @property loading Whether the first load is still running.
 * @property error Why lotteries could not be loaded, or null.
 * @property items The owner's lotteries: open first, then upcoming, awaiting draw, and drawn.
 */
data class OwnerLotteriesState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<OwnerLotteryItem> = emptyList(),
)

/**
 * Lists the signed-in owner's lotteries; the entry point to S15 (create) and S16 (manage).
 * @property repository Reads lotteries.
 * @property loadCampsites Loads the owner's campsites, used only for display names.
 * @property now The current time; replaced in tests.
 */
class OwnerLotteriesController(
    private val repository: LotteryRepository,
    private val loadCampsites: suspend () -> List<Campsite>,
    private val now: () -> Date = { Date() },
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(OwnerLotteriesState())
    /** Read-only state collected by the screen. */
    val state = mutableState.asStateFlow()
    /** Current load request, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads the list, keeping the old items on screen while it loads. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = state.value.items.isEmpty(), error = null)
        request = viewModelScope.launch {
            mutableState.value = try {
                val lotteries = repository.getMyLotteries()
                // Names are only cosmetic, so a failure here still shows the list.
                val names = try {
                    loadCampsites().associate { it.id to it.name }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyMap()
                }
                val time = now().time
                OwnerLotteriesState(
                    loading = false,
                    items = lotteries
                        .map { OwnerLotteryItem(it, lotteryTitle(it, names), lotteryBadge(it, time)) }
                        .sortedWith(compareBy({ it.badge.ordinal }, { -it.lottery.registrationStartAt.toDate().time })),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value.copy(loading = false, error = "Could not load your lotteries. Check your connection and retry.")
            }
        }
    }
}
