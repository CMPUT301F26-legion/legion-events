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

sealed interface OnboardingState {
    data object Loading : OnboardingState
    data class Error(val message: String) : OnboardingState
    data object Blocked : OnboardingState
    data class Ready(val profile: UserProfile) : OnboardingState
}

/** MVC controller retained by the activity's ViewModelStore across recreation. */
class OnboardingController(private val repository: UserRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<OnboardingState>(OnboardingState.Loading)
    val state = mutableState.asStateFlow()

    init {
        initialize()
    }

    fun retry() {
        if (state.value is OnboardingState.Error || state.value is OnboardingState.Blocked) {
            // Set synchronously so rapid taps cannot start multiple jobs.
            mutableState.value = OnboardingState.Loading
            initialize()
        }
    }

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

    class Factory(private val repository: UserRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingController::class.java))
            @Suppress("UNCHECKED_CAST")
            return OnboardingController(repository) as T
        }
    }
}
