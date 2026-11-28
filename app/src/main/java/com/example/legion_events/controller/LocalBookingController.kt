/* Runs booking automation only during an authenticated foreground session. */
package com.example.legion_events.controller

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.LocalBookingRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Rechecks on reopening and every five seconds; failures are visible and never imply success. */
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
            while (true) {
                val failures = try {
                    val result = repository.process(userId)
                    if (result.completed > 0) mutableRevision.value++
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
                delay(5_000)
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
}
