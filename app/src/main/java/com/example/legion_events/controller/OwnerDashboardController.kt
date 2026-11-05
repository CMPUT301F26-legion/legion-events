package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.Lottery
import com.example.legion_events.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A campsite's lottery state, shown as a badge on S12 and S14.
 * @property label The badge text.
 */
enum class LotteryBadge(val label: String) {
    /** Registration is open now. */
    OPEN("Lottery open"),
    /** A lottery is scheduled but registration has not started. */
    UPCOMING("Lottery upcoming"),
    /** Registration has closed and the draw has not run yet. */
    CLOSED("Awaiting draw"),
    /** Winners have been drawn. */
    DRAWN("Drawn"),
    /** No lottery includes this campsite. */
    NONE("No lottery"),
}

/**
 * Chooses the badge for a campsite from the lotteries that include it.
 * The most active state wins: open, then upcoming, then awaiting draw, then drawn.
 * @param campsiteId The campsite document ID.
 * @param lotteries The owner's lotteries.
 * @param now The current time in epoch milliseconds.
 * @return The campsite's badge.
 */
internal fun lotteryBadge(campsiteId: String, lotteries: List<Lottery>, now: Long): LotteryBadge =
    lotteries.filter { campsiteId in it.campsiteIds }.map { lotteryBadge(it, now) }.minOrNull() ?: LotteryBadge.NONE

/**
 * Finds a lottery's state at a given time.
 * @param lottery The lottery.
 * @param now The current time in epoch milliseconds.
 * @return Its badge; never [LotteryBadge.NONE].
 */
internal fun lotteryBadge(lottery: Lottery, now: Long): LotteryBadge = when {
    lottery.winnersDrawn -> LotteryBadge.DRAWN
    now < lottery.registrationStartAt.toDate().time -> LotteryBadge.UPCOMING
    now < lottery.registrationEndAt.toDate().time -> LotteryBadge.OPEN
    else -> LotteryBadge.CLOSED
}

/**
 * One card on the owner dashboard.
 * @property campsite The listing.
 * @property badge Its lottery state, or null when lotteries could not be loaded.
 */
data class OwnerCampsiteItem(val campsite: Campsite, val badge: LotteryBadge?)

/**
 * What the owner dashboard (S12) displays.
 * @property loading Whether the first load is still running.
 * @property error Why campsites could not be loaded, or null.
 * @property campsites The owner's campsites with lottery badges.
 * @property openLotteries How many of the owner's lotteries are open for registration now.
 * @property lotteriesUnavailable Whether lottery states failed to load; campsites are still shown.
 * @property covers Cover photos by photo ID, added after the campsites appear.
 */
data class OwnerDashboardState(
    val loading: Boolean = true,
    val error: String? = null,
    val campsites: List<OwnerCampsiteItem> = emptyList(),
    val openLotteries: Int = 0,
    val lotteriesUnavailable: Boolean = false,
    val covers: Map<String, Photo> = emptyMap(),
)

/**
 * Loads the signed-in user's campsites and lottery states for S12.
 * The screen calls [refresh] each time it resumes, so new or edited listings appear on return.
 * @property repository Reads the owner's campsites, lotteries, and photos.
 * @property now Supplies the current time, replaceable in tests.
 */
class OwnerDashboardController(
    private val repository: OwnerCampsiteRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow(OwnerDashboardState())
    /** Read-only state collected by the dashboard. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before the next one starts. */
    private var request: Job? = null

    /** Reloads everything, keeping the current list visible while the request runs. */
    fun refresh() {
        request?.cancel()
        if (state.value.error != null) mutableState.value = OwnerDashboardState()
        request = viewModelScope.launch {
            try {
                val lotteries = async { runCatching { repository.getMyLotteries() } }
                val campsites = repository.getMyCampsites()
                val loaded = lotteries.await().getOrElse { if (it is CancellationException) throw it else null }
                val time = now()
                mutableState.value = state.value.copy(
                    loading = false,
                    error = null,
                    campsites = campsites.map { site -> OwnerCampsiteItem(site, loaded?.let { lotteryBadge(site.id, it, time) }) },
                    openLotteries = loaded.orEmpty().count { lotteryBadge(it, time) == LotteryBadge.OPEN },
                    lotteriesUnavailable = loaded == null,
                )
                loadCovers(campsites)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = OwnerDashboardState(loading = false,
                    error = "Could not load your campsites. Check your connection and retry.")
            }
        }
    }

    /**
     * Loads cover photos not already cached. A failure only leaves placeholders.
     * @param campsites The campsites whose covers are shown.
     */
    private suspend fun loadCovers(campsites: List<Campsite>) {
        val missing = campsites.mapNotNull { it.photoId }.filter { it !in state.value.covers }
        if (missing.isEmpty()) return
        try {
            val photos = repository.getPhotos(missing)
            mutableState.value = state.value.copy(covers = state.value.covers + photos.associateBy { it.id })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cards keep their placeholder image.
        }
    }
}
