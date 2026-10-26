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

internal const val ONBOARDING_ROUTE = "onboarding"
internal const val HOME_ROUTE = "home"

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
