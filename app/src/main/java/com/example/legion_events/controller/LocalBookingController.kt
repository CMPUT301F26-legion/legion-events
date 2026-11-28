/* Runs booking automation only during an authenticated foreground session. */
package com.example.legion_events.controller

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LocalBookingRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rechecks on reopening and whenever an owned reservation changes. Otherwise it polls every five
 * seconds, doubling up to one minute while nothing is processed, to limit Firestore reads.
 * Failures are visible and never imply success.
 */
class LocalBookingController(
    private val repository: LocalBookingRepository,
    private val log: (Throwable) -> Unit = { Log.w("LocalBookings", it.message, it) },
) : ViewModel() {
    private var ownerId: String? = null
    private var session: Job? = null
    private var dismissed: String? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()

    fun start(userId: String) {
        if (ownerId == userId && session?.isActive == true) return
        stop()
        ownerId = userId
        session = viewModelScope.launch {
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch {
                // The first snapshot is the state the first check already reads. Polling continues if the listener fails.
                repository.reservationChanges(userId).drop(1).catch { log(it) }.collect { wake.trySend(Unit) }
            }
            var wait = MIN_CHECK_INTERVAL_MS
            while (true) {
                val failures = try {
                    val result = repository.process(userId)
                    if (result.completed > 0) {
                        mutableRevision.value++
                        wait = MIN_CHECK_INTERVAL_MS
                    }
                    result.failures
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { listOf(error) }
                failures.forEach(log)
                val message = failures.firstOrNull()?.let { failure ->
                    val causes = generateSequence(failure) { it.cause }.toList()
                    if (causes.any { it is FirebaseFirestoreException && it.code == FirebaseFirestoreException.Code.PERMISSION_DENIED })
                        "Booking processing needs updated permissions or an eligible entrant. Update the booking rules, then retry."
                    else "Some bookings could not be processed. Check your connection or saved booking details. Retrying automatically."
                }
                if (message == null) dismissed = null
                mutableError.value = message.takeIf { it != dismissed }
                withTimeoutOrNull(wait) { wake.receive() }
                wait = minOf(wait * 2, MAX_CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        session?.cancel()
        session = null
        ownerId = null
        dismissed = null
        mutableError.value = null
    }

    fun retry() { val uid = ownerId ?: return; stop(); start(uid) }
    fun dismiss() { dismissed = mutableError.value; mutableError.value = null }

    companion object {
        /** Poll interval after processing something, and the first interval after starting. */
        const val MIN_CHECK_INTERVAL_MS = 5_000L
        /** Longest poll interval while idle; owned reservation changes still wake the processor at once. */
        const val MAX_CHECK_INTERVAL_MS = 60_000L
    }
}
