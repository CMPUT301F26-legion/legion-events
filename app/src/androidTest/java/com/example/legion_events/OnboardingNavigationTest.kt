package com.example.legion_events

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.UserInitializationException
import com.example.legion_events.data.UserRepository
import com.example.legion_events.model.UserProfile
import com.example.legion_events.ui.theme.LegioneventsTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Uses a fake repository so UI checks never create accounts or alter production data. */
class OnboardingNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingWaitsForSuccessAndHomeHasNoOnboardingBackEntry() {
        val result = CompletableDeferred<UserProfile>()
        val repository = FakeRepository { result.await() }
        lateinit var controller: OnboardingController
        lateinit var navigation: NavHostController
        compose.runOnUiThread { controller = OnboardingController(repository) }
        compose.setContent {
            navigation = rememberNavController()
            LegioneventsTheme { AppNavigation(controller, navigation) }
        }
        compose.onNodeWithText("Opening your account…").assertIsDisplayed()
        compose.runOnIdle { assertEquals("onboarding", navigation.currentDestination?.route) }

        result.complete(UserProfile(uid = "entrant"))
        waitForHome()
        compose.runOnIdle {
            assertEquals("home", navigation.currentDestination?.route)
            assertNull(navigation.previousBackStackEntry)
            assertEquals(1, repository.calls)
        }
    }

    @Test
    fun failureStaysOnOnboardingAndRetryCanReachHome() {
        var fail = true
        val repository = FakeRepository {
            if (fail) throw UserInitializationException("Connection unavailable. Please retry.")
            UserProfile(uid = "entrant")
        }
        lateinit var controller: OnboardingController
        compose.runOnUiThread { controller = OnboardingController(repository) }
        compose.setContent { LegioneventsTheme { AppNavigation(controller) } }
        compose.onNodeWithText("Connection unavailable. Please retry.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Legion Events").assertDoesNotExist()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Retry").performClick()
        waitForHome()
        compose.runOnIdle { assertEquals(2, repository.calls) }
    }

    @Test
    fun disabledProfileNeverShowsHome() {
        lateinit var controller: OnboardingController
        compose.runOnUiThread {
            controller = OnboardingController(FakeRepository { UserProfile(uid = "entrant", disabled = true) })
        }
        compose.setContent { LegioneventsTheme { AppNavigation(controller) } }
        compose.onNodeWithText("Account disabled").assertIsDisplayed()
        compose.onNodeWithText("Check again").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Legion Events").assertDoesNotExist()
    }

    @Test
    fun restoringHomeDoesNotRepeatInitializationOrNavigation() {
        val restoration = StateRestorationTester(compose)
        val repository = FakeRepository { UserProfile(uid = "entrant") }
        lateinit var controller: OnboardingController
        lateinit var navigation: NavHostController
        compose.runOnUiThread { controller = OnboardingController(repository) }
        restoration.setContent {
            navigation = rememberNavController()
            LegioneventsTheme { AppNavigation(controller, navigation) }
        }
        waitForHome()
        restoration.emulateSavedInstanceStateRestore()
        waitForHome()
        compose.runOnIdle {
            assertEquals(1, repository.calls)
            assertEquals("home", navigation.currentDestination?.route)
            assertNull(navigation.previousBackStackEntry)
        }
    }

    @Test
    fun restoredHomeWaitsForTheNewProcessToCheckTheProfile() {
        val restoration = StateRestorationTester(compose)
        val currentController = mutableStateOf<OnboardingController?>(null)
        compose.runOnUiThread {
            currentController.value = OnboardingController(FakeRepository { UserProfile(uid = "entrant") })
        }
        restoration.setContent {
            LegioneventsTheme { AppNavigation(checkNotNull(currentController.value)) }
        }
        waitForHome()

        val pending = CompletableDeferred<UserProfile>()
        compose.runOnUiThread {
            currentController.value = OnboardingController(FakeRepository { pending.await() })
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Opening your account…").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Legion Events").assertDoesNotExist()
        pending.complete(UserProfile(uid = "entrant", disabled = true))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Account disabled").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Account disabled").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Legion Events").assertDoesNotExist()
    }

    private fun waitForHome() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Welcome to Legion Events").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Welcome to Legion Events").assertIsDisplayed()
    }

    private class FakeRepository(private val initialize: suspend () -> UserProfile) : UserRepository {
        var calls = 0
        override suspend fun initializeUser(): UserProfile {
            calls++
            return initialize()
        }
    }
}
