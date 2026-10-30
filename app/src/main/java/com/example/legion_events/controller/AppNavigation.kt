package com.example.legion_events.controller

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.view.HomeScreen
import com.example.legion_events.view.CampsiteDetailsScreen
import com.example.legion_events.view.OnboardingScreen
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.FirebaseRepository
import kotlinx.coroutines.flow.filterIsInstance

/** Route shown while the app opens or creates the user's account. */
internal const val ONBOARDING_ROUTE = "onboarding"
/** Route shown after account and profile setup succeeds. */
internal const val HOME_ROUTE = "home"
/** Details route; only a document ID is saved in the navigation arguments. */
internal const val CAMPSITE_ROUTE = "campsite/{campsiteId}"

/**
 * Shows onboarding until the account is ready, then routes between search and campsite details.
 *
 * Removes onboarding from the back stack so Back from Home leaves the app.
 * A restored Home also waits for the current process to finish checking the profile.
 *
 * @param controller Provides the setup state and handles Retry actions.
 * @param navController Moves between screens and remembers the back stack.
 * @param campsites Supplies active campsite listings for the home screen.
 * @param details Supplies the selected campsite and its photos.
 */
@Composable
fun AppNavigation(
    controller: OnboardingController,
    navController: NavHostController = rememberNavController(),
    campsites: CampsiteRepository = FirebaseRepository.instance,
    details: CampsiteDetailsRepository = FirebaseRepository.instance,
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
            if (state is OnboardingState.Ready) {
                val search: CampsiteListController = viewModel(factory = viewModelFactory {
                    initializer { CampsiteListController(campsites, createSavedStateHandle()) }
                })
                val listings by search.state.collectAsStateWithLifecycle()
                HomeScreen(listings, search::search, search::filter, search::retry) { campsiteId ->
                    navController.navigate("campsite/${Uri.encode(campsiteId)}") { launchSingleTop = true }
                }
            } else OnboardingScreen(state, controller::retry)
        }
        composable(CAMPSITE_ROUTE, arguments = listOf(navArgument("campsiteId") { type = NavType.StringType })) { entry ->
            // Restored details must pass the same account check as a restored Home.
            if (state is OnboardingState.Ready) {
                val detail: CampsiteDetailsController = viewModel(factory = viewModelFactory {
                    initializer { CampsiteDetailsController(checkNotNull(entry.arguments?.getString("campsiteId")), details) }
                })
                val information by detail.state.collectAsStateWithLifecycle()
                CampsiteDetailsScreen(information, { navController.popBackStack() }, detail::retry, detail::retryPhotos)
            } else OnboardingScreen(state, controller::retry)
        }
    }
}
