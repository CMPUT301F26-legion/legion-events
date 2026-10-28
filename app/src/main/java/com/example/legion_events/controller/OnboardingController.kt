package com.example.legion_events.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.legion_events.data.AccountDisabledException
import com.example.legion_events.data.UserInitializationException
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The account setup state that the screen should display. */
sealed interface OnboardingState {
    /** Account or profile setup is still running. */
    data object Loading : OnboardingState
    /**
     * Setup failed and the user can retry.
     * @property message A plain-language explanation to show on the screen.
     */
    data class Error(val message: String) : OnboardingState
    /** The account is disabled, so the user cannot enter Home. */
    data object Blocked : OnboardingState
    /**
     * Setup succeeded and Home can be opened.
     * @property profile The user's saved profile.
     */
    data class Ready(val profile: UserProfile) : OnboardingState
}

/**
 * Opens the user's account and gives the views their loading, error, or ready state.
 *
 * Android keeps this controller during screen recreation so setup is not repeated.
 *
 * @constructor Starts account setup as soon as this controller is created.
 * @property repository Handles authentication and profile storage.
 */
class OnboardingController(private val repository: UserRepository) : ViewModel() {
    /** State that only this controller can change. */
    private val mutableState = MutableStateFlow<OnboardingState>(OnboardingState.Loading)
    /** Read-only setup state for the views and navigation. */
    val state = mutableState.asStateFlow()

    init {
        initialize()
    }

    /**
     * Tries setup again after an error or a disabled-account check.
     *
     * Ignores taps while setup is running or after it succeeds to avoid duplicate work.
     */
    fun retry() {
        if (state.value is OnboardingState.Error || state.value is OnboardingState.Blocked) {
            // Set synchronously so rapid taps cannot start multiple jobs.
            mutableState.value = OnboardingState.Loading
            initialize()
        }
    }

    /** Opens the account in a lifecycle-aware coroutine and updates the screen state. */
    private fun initialize() {
        viewModelScope.launch {
            mutableState.value = try {
                val profile = repository.initializeUser()
                if (profile.disabled) OnboardingState.Blocked else OnboardingState.Ready(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AccountDisabledException) {
                OnboardingState.Blocked
            } catch (error: UserInitializationException) {
                OnboardingState.Error(error.message ?: "Could not open your account. Please retry.")
            } catch (_: Exception) {
                OnboardingState.Error("Could not open your account. Check your connection and retry.")
            }
        }
    }

    /**
     * Lets Android create and retain an onboarding controller with its repository.
     * @property repository The account storage service to give to the controller.
     */
    class Factory(private val repository: UserRepository) : ViewModelProvider.Factory {
        /**
         * Creates the requested onboarding controller.
         * @param T The ViewModel type requested by Android.
         * @param modelClass The class Android wants this factory to create.
         * @return A controller that uses this factory's repository.
         * @throws IllegalArgumentException If the requested class is not supported.
         */
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingController::class.java))
            @Suppress("UNCHECKED_CAST")
            return OnboardingController(repository) as T
        }
    }
}
