/*
 * OwnerNoticesController.kt
 *
 * Purpose: S18 owner notices: send a notice to a lottery's waitlist and list sent notices
 *   (US 02.10.01, 02.10.02).
 * Role: Controller in MVC; OwnerNoticesScreen renders its state and OwnerNoticeRepository saves notices.
 * Outstanding issues: Messaging individual booked entrants (US 02.12.01) is a later part.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.OwnerNoticeRepository
import com.example.legion_events.model.Campsite
import com.example.legion_events.model.MAX_NOTICE_LENGTH
import com.example.legion_events.model.OwnerNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A lottery the owner can send a notice to.
 * @property id Lottery document ID.
 * @property label The lottery's campsite names, for display.
 * @property entrantCount Entrants on its waitlist.
 */
data class NoticeTarget(val id: String, val label: String, val entrantCount: Int)

/**
 * What S18 shows.
 * @property loading Whether lotteries and sent notices are loading.
 * @property error Why they could not be loaded, or null.
 * @property targets The owner's lotteries.
 * @property selectedLotteryId Lottery chosen to receive the notice, or null.
 * @property message The notice being written.
 * @property messageError Why the notice cannot be sent, or null.
 * @property sending Whether a notice is being sent.
 * @property result Confirmation after sending, or null.
 * @property sent Notices this owner sent, newest first, with their lottery labels.
 */
data class OwnerNoticesState(
    val loading: Boolean = true,
    val error: String? = null,
    val targets: List<NoticeTarget> = emptyList(),
    val selectedLotteryId: String? = null,
    val message: String = "",
    val messageError: String? = null,
    val sending: Boolean = false,
    val result: String? = null,
    val sent: List<Pair<OwnerNotice, String>> = emptyList(),
)

/**
 * Loads the owner's lotteries and sent notices, and sends a notice to one lottery's waitlist.
 * @property lotteries Reads the owner's lotteries.
 * @property loadCampsites Reads the owner's campsites, to name each lottery.
 * @property notices Sends and lists notices.
 */
class OwnerNoticesController(
    private val lotteries: LotteryRepository,
    private val loadCampsites: suspend () -> List<Campsite>,
    private val notices: OwnerNoticeRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OwnerNoticesState())
    /** State collected by S18. */
    val state = mutableState.asStateFlow()
    /** Current load, cancelled before reloading. */
    private var request: Job? = null

    init { refresh() }

    /** Reloads lotteries and sent notices, keeping the notice being written. */
    fun refresh() {
        request?.cancel()
        mutableState.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                coroutineScope {
                    val owned = async { lotteries.getMyLotteries() }
                    val sites = async { loadCampsites().associate { it.id to it.name } }
                    val sent = async { notices.getSentNotices() }
                    val names = sites.await()
                    val targets = owned.await().sortedByDescending { it.registrationEndAt }.map { lottery ->
                        NoticeTarget(lottery.id,
                            lottery.campsiteIds.mapNotNull(names::get).joinToString().ifEmpty { "Lottery" },
                            lottery.entrantCount)
                    }
                    val labels = targets.associate { it.id to it.label }
                    val selected = state.value.selectedLotteryId?.takeIf { id -> targets.any { it.id == id } }
                        ?: targets.firstOrNull()?.id
                    mutableState.value = state.value.copy(loading = false, targets = targets,
                        selectedLotteryId = selected,
                        sent = sent.await().map { it to (labels[it.lotteryId] ?: "Lottery") })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, error = "Could not load your notices. Please retry.")
            }
        }
    }

    /**
     * Chooses which lottery's waitlist receives the notice.
     * @param lotteryId One of [OwnerNoticesState.targets].
     */
    fun selectLottery(lotteryId: String) {
        mutableState.value = state.value.copy(selectedLotteryId = lotteryId, messageError = null, result = null)
    }

    /**
     * Updates the notice text.
     * @param message The text field's current value.
     */
    fun setMessage(message: String) {
        mutableState.value = state.value.copy(message = message.take(MAX_NOTICE_LENGTH), messageError = null, result = null)
    }

    /** Checks the notice, sends it to the selected waitlist, and reloads the sent list. */
    fun send() {
        val current = state.value
        if (current.sending) return
        val lotteryId = current.selectedLotteryId
        val error = when {
            lotteryId == null -> "Create a lottery first."
            current.message.isBlank() -> "Write a notice first."
            else -> null
        }
        if (error != null) {
            mutableState.value = current.copy(messageError = error)
            return
        }
        mutableState.value = current.copy(sending = true, messageError = null, result = null)
        viewModelScope.launch {
            try {
                val count = notices.sendNotification(checkNotNull(lotteryId), current.message)
                mutableState.value = state.value.copy(sending = false, message = "",
                    result = "Sent to $count ${if (count == 1) "entrant" else "entrants"}.")
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                mutableState.value = state.value.copy(sending = false, messageError = error.message)
            } catch (_: Exception) {
                mutableState.value = state.value.copy(sending = false,
                    messageError = "Could not send the notice. Check your connection and retry.")
            }
        }
    }
}
