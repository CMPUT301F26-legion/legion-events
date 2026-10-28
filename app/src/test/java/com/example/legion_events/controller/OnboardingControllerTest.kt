package com.example.legion_events.controller

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.legion_events.data.AccountDisabledException
import com.example.legion_events.data.UserInitializationException
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun waitsForProfileBeforeBecomingReady() = runTest(dispatcher) {
        val result = CompletableDeferred<UserProfile>()
        val repository = FakeRepository { result.await() }
        val controller = controller(repository)
        runCurrent()
        assertEquals(OnboardingState.Loading, controller.state.value)
        controller.retry()
        runCurrent()
        assertEquals(1, repository.calls)

        val profile = UserProfile(uid = "entrant")
        result.complete(profile)
        runCurrent()
        assertEquals(OnboardingState.Ready(profile), controller.state.value)
        controller.retry()
        runCurrent()
        assertEquals(1, repository.calls)
    }

    @Test
    fun errorCanRetryWithoutDuplicateJobsOrPrematureSuccess() = runTest(dispatcher) {
        val result = CompletableDeferred<UserProfile>()
        var fail = true
        val repository = FakeRepository {
            if (fail) throw UserInitializationException("Check your connection and retry.")
            result.await()
        }
        val controller = controller(repository)
        runCurrent()
        assertEquals(OnboardingState.Error("Check your connection and retry."), controller.state.value)

        fail = false
        repeat(10) { controller.retry() }
        runCurrent()
        assertEquals(2, repository.calls)
        assertEquals(OnboardingState.Loading, controller.state.value)
        result.complete(UserProfile(uid = "same-entrant"))
        runCurrent()
        assertEquals("same-entrant", (controller.state.value as OnboardingState.Ready).profile.uid)
    }

    @Test
    fun disabledProfileIsBlockedAndCanBeRechecked() = runTest(dispatcher) {
        var disabled = true
        val controller = controller(FakeRepository { UserProfile(uid = "entrant", disabled = disabled) })
        runCurrent()
        assertEquals(OnboardingState.Blocked, controller.state.value)
        disabled = false
        controller.retry()
        runCurrent()
        assertEquals(OnboardingState.Ready(UserProfile(uid = "entrant")), controller.state.value)
    }

    @Test
    fun disabledAuthenticationIsBlocked() = runTest(dispatcher) {
        val controller = controller(FakeRepository { throw AccountDisabledException() })
        runCurrent()
        assertEquals(OnboardingState.Blocked, controller.state.value)
    }

    @Test
    fun unexpectedFailureOffersRetry() = runTest(dispatcher) {
        val controller = controller(FakeRepository { throw IllegalStateException("Internal details") })
        runCurrent()
        assertEquals(
            OnboardingState.Error("Could not open your account. Check your connection and retry."),
            controller.state.value,
        )
    }

    @Test
    fun recreatedActivityUsesRetainedControllerAndDoesNotInitializeAgain() = runTest(dispatcher) {
        val repository = FakeRepository { UserProfile(uid = "entrant", name = "Saved name", role = "admin") }
        val first = controller(repository)
        runCurrent()
        val recreated = controller(repository)
        runCurrent()
        assertSame(first, recreated)
        assertEquals(1, repository.calls)
        assertEquals("admin", (recreated.state.value as OnboardingState.Ready).profile.role)
        assertEquals("Saved name", (recreated.state.value as OnboardingState.Ready).profile.name)
    }

    @Test
    fun clearingControllerCancelsInitializationWithoutReportingAnError() = runTest(dispatcher) {
        val pending = CompletableDeferred<UserProfile>()
        val controller = controller(FakeRepository { pending.await() })
        runCurrent()
        store.clear()
        runCurrent()
        assertEquals(OnboardingState.Loading, controller.state.value)
    }

    private fun controller(repository: UserRepository): OnboardingController =
        ViewModelProvider.create(store, OnboardingController.Factory(repository))[OnboardingController::class]

    private class FakeRepository(private val initialize: suspend () -> UserProfile) : UserRepository {
        var calls = 0
        override suspend fun initializeUser(): UserProfile {
            calls++
            return initialize()
        }
    }
}
