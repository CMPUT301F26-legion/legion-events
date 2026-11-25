/*
 * LocalLotteryDrawController.kt
 * Purpose: Checks owned lottery deadlines while the signed-in owner's app is in the foreground.
 * Role: Controller that combines saved deadline changes with a five-second clock check.
 */
package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LocalLotteryDrawRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Checks deadlines on start, when a saved lottery changes, and every five seconds while active.
 * @property repository Observes and draws only lotteries belonging to the signed-in owner.
 * @property now Clock used to identify due lotteries. Firebase rules enforce the server deadline.
 */
class LocalLotteryDrawController(
    private val repository: LocalLotteryDrawRepository,
    private val now: () -> Timestamp = Timestamp::now,
) : ViewModel() {
    private var ownerId: String? = null
    private var session: Job? = null
    private val mutableError = MutableStateFlow<String?>(null)
    /** A draw or listener failure shown with Retry so failures are visible to the owner. */
    val error = mutableError.asStateFlow()
    private val mutableCompletedDraws = MutableStateFlow(0)
    /** Changes after a committed local draw so visible owner screens can refresh their results. */
    val completedDraws = mutableCompletedDraws.asStateFlow()

    /** Starts the authenticated foreground session, or keeps the existing session for the same UID. */
    fun start(userId: String) {
        if (ownerId == userId && session?.isActive == true) return
        stop()
        ownerId = userId
        session = viewModelScope.launch {
            val ticks = flow {
                while (true) {
                    emit(Unit)
                    delay(CHECK_INTERVAL_MS)
                }
            }
            repository.observeOwnedLotteries(userId)
                .combine(ticks) { lotteries, _ -> lotteries }
                .retryWhen { failure, _ ->
                    mutableError.value = message(failure)
                    delay(CHECK_INTERVAL_MS)
                    true
                }
                .collect { lotteries ->
                    var failure: String? = null
                    for (lottery in lotteries) {
                        if (lottery.registrationEndAt?.let { it > now() } == true) continue
                        try {
                            if (repository.draw(lottery.id)) mutableCompletedDraws.value++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = message(error)
                        }
                    }
                    mutableError.value = failure
                }
        }
    }

    /** Ends the foreground session when the app is hidden or the account changes. */
    fun stop() {
        session?.cancel()
        session = null
        ownerId = null
        mutableError.value = null
    }

    /** Starts a fresh listener and immediate check for the current owner. */
    fun retry() {
        val userId = ownerId ?: return
        stop()
        start(userId)
    }

    /** Maps permission errors separately so an owner knows when the team must apply the draw rules. */
    private fun message(error: Throwable): String =
        if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            "Lottery draw permission is missing. Ask the project administrator to update the draw rules."
        } else {
            "Could not finish a lottery draw. Check your connection and retry."
        }

    /** Current trigger selection. Cloud-only code is retained in the standalone lottery-worker folder. */
    companion object {
        /** Enables foreground owner draws; set false when switching to cloud-only triggering. */
        const val ENABLED = true
        private const val CHECK_INTERVAL_MS = 5_000L
    }
}
