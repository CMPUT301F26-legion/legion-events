package com.example.legion_events.controller

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.view.HomeScreen
import com.example.legion_events.view.OnboardingScreen
import kotlinx.coroutines.flow.filterIsInstance

/** Route shown while the app opens or creates the user's account. */
internal const val ONBOARDING_ROUTE = "onboarding"
/** Route shown after account and profile setup succeeds. */
internal const val HOME_ROUTE = "home"

/**
 * Shows onboarding until the account is ready, then opens Home.
 *
 * Removes onboarding from the back stack so Back from Home leaves the app.
 * A restored Home also waits for the current process to finish checking the profile.
 *
 * @param controller Provides the setup state and handles Retry actions.
 * @param navController Moves between onboarding and Home and remembers the back stack.
 */
@Composable
fun AppNavigation(
    controller: OnboardingController,
    navController: NavHostController = rememberNavController(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    NavHost(navController = navController, startDestination = ONBOARDING_ROUTE) {
        composable(ONBOARDING_ROUTE) {
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(controller, lifecycleOwner, navController) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    controller.state.filterIsInstance<OnboardingState.Ready>().collect {
                        if (navController.currentDestination?.route == ONBOARDING_ROUTE) {
                            navController.navigate(HOME_ROUTE) {
                                popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
            OnboardingScreen(state, controller::retry)
        }
        composable(HOME_ROUTE) {
            // Process restoration may restore Home before this process has checked the profile.
            if (state is OnboardingState.Ready) HomeScreen()
            else OnboardingScreen(state, controller::retry)
        }
    }
}
