/*
 * LocalLotteryDrawController.kt
 * Purpose: Checks owned lottery deadlines while the signed-in owner's app is in the foreground.
 * Role: Controller that combines saved deadline changes with a five-second clock check.
 */
package com.example.legion_events.controller

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LocalLotteryDrawCandidate
import com.example.legion_events.data.LocalLotteryDrawException
import com.example.legion_events.data.LocalLotteryDrawFailureKind
import com.example.legion_events.data.LocalLotteryDrawRepository
import com.example.legion_events.data.LotteryWaitlistChangedException
import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Checks deadlines on start, when a saved lottery changes, and every five seconds while active.
 * Transient failures are retried on the next check. A failure that cannot succeed by retrying is not
 * attempted again until the lottery's saved data changes or the owner taps Retry.
 * @property repository Observes and draws only lotteries belonging to the signed-in owner.
 * @property now Clock used to identify due lotteries. Firebase rules enforce the server deadline.
 * @property log Records each failure with its lottery ID; replaceable because unit tests have no Android log.
 */
class LocalLotteryDrawController(
    private val repository: LocalLotteryDrawRepository,
    private val now: () -> Timestamp = Timestamp::now,
    private val log: (String, Throwable) -> Unit = { message, error -> Log.w(TAG, message, error) },
) : ViewModel() {
    private var ownerId: String? = null
    private var session: Job? = null
    /** Permanently failed lotteries, keyed by ID, with the saved version that failed. */
    private val blocked = mutableMapOf<String, LocalLotteryDrawCandidate>()
    /** Current failures in check order, so the notice describes every lottery still failing. */
    private val failures = linkedMapOf<String, Failure>()
    private val names = mutableMapOf<String, String>()
    private var current: String? = null
    private var dismissed: String? = null
    private val mutableError = MutableStateFlow<String?>(null)
    /** A draw or listener failure naming the affected lottery, shown with Retry and a dismiss action. */
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
                    if (failure is CancellationException) return@retryWhen false
                    log("Owned lottery listener failed", failure)
                    val kind = failure.kind()
                    show(listenerMessage(kind))
                    if (kind != LocalLotteryDrawFailureKind.RETRYABLE) return@retryWhen false
                    delay(CHECK_INTERVAL_MS)
                    true
                }
                .catch { /* Permanent listener failure: already logged and shown; Retry starts over. */ }
                .collect(::check)
        }
    }

    /** Ends the foreground session when the app is hidden or the account changes. */
    fun stop() {
        session?.cancel()
        session = null
        ownerId = null
        blocked.clear()
        failures.clear()
        names.clear()
        current = null
        dismissed = null
        mutableError.value = null
    }

    /** Starts a fresh listener and immediate check, including lotteries whose draw failed permanently. */
    fun retry() {
        val userId = ownerId ?: return
        stop()
        start(userId)
    }

    /** Hides the current notice. It returns only when the set of failing lotteries or their reasons changes. */
    fun dismiss() {
        dismissed = current
        mutableError.value = null
    }

    /** Attempts every due lottery, so one failure does not block the others. */
    private suspend fun check(lotteries: List<LocalLotteryDrawCandidate>) {
        val present = lotteries.associateBy { it.id }
        // A saved change (edit, join, deadline move) may have fixed a permanent failure.
        blocked.entries.removeAll { (id, failed) -> present[id] != failed }
        failures.keys.retainAll(present.keys)
        for (lottery in lotteries) {
            if (lottery.id in blocked) continue
            if (lottery.registrationEndAt?.let { it > now() } == true) {
                failures.remove(lottery.id)
                continue
            }
            try {
                if (repository.draw(lottery.id)) mutableCompletedDraws.value++
                failures.remove(lottery.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val kind = error.kind()
                val permanent = kind != LocalLotteryDrawFailureKind.RETRYABLE
                log("Draw failed for lottery ${lottery.id} (${if (permanent) "not retrying" else "will retry"})", error)
                if (permanent) blocked[lottery.id] = lottery
                failures[lottery.id] = Failure(nameOf(lottery.id), kind, error.cause ?: error)
            }
        }
        show(drawMessage(failures.values.toList()))
    }

    /** Publishes [message] unless the owner already dismissed this exact notice. */
    private fun show(message: String?) {
        current = message
        if (message == null) dismissed = null
        mutableError.value = message.takeIf { it != dismissed }
    }

    /** Looks up a display name once per session; falls back to the ID when no name can be read. */
    private suspend fun nameOf(lotteryId: String): String = names.getOrPut(lotteryId) {
        val name = try {
            repository.lotteryName(lotteryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("Could not name lottery $lotteryId", error)
            null
        }
        name?.let { "\"$it\"" } ?: "lottery $lotteryId"
    }

    /**
     * One failed draw.
     * @property name Owner-facing lottery name, or its ID.
     * @property kind Whether retrying can help.
     * @property cause Original error, whose message explains invalid data.
     */
    internal class Failure(val name: String, val kind: LocalLotteryDrawFailureKind, val cause: Throwable) {
        val permanent get() = kind != LocalLotteryDrawFailureKind.RETRYABLE
    }

    /** Current trigger selection. Cloud-only code is retained in the standalone lottery-worker folder. */
    companion object {
        /** Enables foreground owner draws; set false when switching to cloud-only triggering. */
        const val ENABLED = true
        private const val TAG = "LocalLotteryDraw"
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val PERMISSION_HELP = "Ask the project administrator to update the draw rules."

        /** The repository classifies its failures; anything else is unexpected and not retried. */
        private fun Throwable.kind() = (this as? LocalLotteryDrawException)?.kind ?: LocalLotteryDrawFailureKind.INVALID

        /** Names each failing lottery and says whether the app keeps retrying. */
        internal fun drawMessage(failures: List<Failure>): String? {
            if (failures.isEmpty()) return null
            val single = failures.singleOrNull()
            if (single != null) {
                val error = single.cause
                return when {
                    single.kind == LocalLotteryDrawFailureKind.PERMISSION_DENIED ->
                        "Could not draw the ${single.name} lottery: draw permission is missing. $PERMISSION_HELP"
                    error is LotteryWaitlistChangedException ->
                        "Could not draw the ${single.name} lottery because its waiting list changed. It will retry automatically."
                    !single.permanent ->
                        "Could not draw the ${single.name} lottery. Check your connection; it will retry automatically."
                    else -> "Could not draw the ${single.name} lottery: ${reason(error)} " +
                        "It will not retry until the lottery changes or you tap Retry."
                }
            }
            val names = failures.joinToString(", ") { it.name }
            return if (failures.none { it.permanent }) {
                "Could not draw ${failures.size} lotteries: $names. Check your connection; they will retry automatically."
            } else {
                "Could not draw ${failures.size} lotteries: $names. Lotteries with invalid data or missing " +
                    "permission will not retry until they change or you tap Retry."
            }
        }

        /** Describes a listener failure, which affects every owned lottery rather than one. */
        private fun listenerMessage(kind: LocalLotteryDrawFailureKind): String = when (kind) {
            LocalLotteryDrawFailureKind.PERMISSION_DENIED ->
                "Could not check your lotteries: permission is missing. $PERMISSION_HELP"
            LocalLotteryDrawFailureKind.INVALID -> "Could not check your lotteries for draws. Tap Retry to try again."
            LocalLotteryDrawFailureKind.RETRYABLE -> "Could not check your lotteries for draws. Check your connection; it will retry automatically."
        }

        /** Uses the repository's owner-facing check messages and hides SDK internals. */
        private fun reason(error: Throwable): String {
            val message = error.message?.takeIf { error is IllegalStateException || error is IllegalArgumentException }
            return message?.trimEnd('.')?.plus(".") ?: "its saved data could not be drawn."
        }
    }
}
