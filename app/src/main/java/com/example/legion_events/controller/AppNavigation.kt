package com.example.legion_events.controller

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.legion_events.R
import com.example.legion_events.view.HomeScreen
import com.example.legion_events.view.CampsiteDetailsScreen
import com.example.legion_events.view.OnboardingScreen
import com.example.legion_events.view.PlaceholderScreen
import com.example.legion_events.view.PolicyScreen
import com.example.legion_events.view.ProfileScreen
import com.example.legion_events.view.WelcomeScreen
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.FirebaseProfileRepository
import com.example.legion_events.data.FirebaseRepository
import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.data.WelcomePreferences
import kotlinx.coroutines.flow.filterIsInstance

/** Route shown while the app opens or creates the user's account. */
internal const val ONBOARDING_ROUTE = "onboarding"
/** S01: welcome and device identity, shown once per device after setup. */
internal const val WELCOME_ROUTE = "welcome"
/** S02/S03: route shown after account and profile setup succeeds. */
internal const val HOME_ROUTE = "home"
/** S04: details route; only a document ID is saved in the navigation arguments. */
internal const val CAMPSITE_ROUTE = "campsite/{campsiteId}"
/** App policy and guidelines, opened from S01 and S11. */
internal const val POLICY_ROUTE = "policy"
/** S11: profile and settings. */
internal const val PROFILE_ROUTE = "profile"

/** S05: scan a campsite QR code. */
internal const val QR_SCAN_ROUTE = "qr_scan"
/** S06: the entrant's lotteries. */
internal const val LOTTERIES_ROUTE = "lotteries"
/** S07: notification inbox. */
internal const val INBOX_ROUTE = "inbox"
/** S08: choose a campsite and dates from a booking offer. */
internal const val BOOKING_OFFER_ROUTE = "booking_offer"
/** S09: reservation confirmation. */
internal const val RESERVATION_CONFIRMATION_ROUTE = "reservation_confirmation"
/** S10: the entrant's reservations. */
internal const val BOOKINGS_ROUTE = "bookings"
/** S10b: one reservation's details. */
internal const val RESERVATION_DETAILS_ROUTE = "reservation_details"
/** S10c: rate a completed stay. */
internal const val RATE_STAY_ROUTE = "rate_stay"
/** S11b: accessibility settings. */
internal const val ACCESSIBILITY_ROUTE = "accessibility"
/** S12: owner dashboard. */
internal const val OWNER_DASHBOARD_ROUTE = "owner"
/** S13: add or edit a campsite. */
internal const val OWNER_CAMPSITE_EDIT_ROUTE = "owner/campsite_edit"
/** S14: owner campsite details. */
internal const val OWNER_CAMPSITE_ROUTE = "owner/campsite"
/** S15: create or edit a lottery. */
internal const val LOTTERY_EDIT_ROUTE = "owner/lottery_edit"
/** S16: lottery management. */
internal const val LOTTERY_MANAGEMENT_ROUTE = "owner/lottery"
/** S17: owner reservations. */
internal const val OWNER_RESERVATIONS_ROUTE = "owner/reservations"
/** S18: owner notifications. */
internal const val OWNER_NOTIFICATIONS_ROUTE = "owner/notifications"
/** S19: admin dashboard. */
internal const val ADMIN_DASHBOARD_ROUTE = "admin"
/** S20: admin logs. */
internal const val ADMIN_LOGS_ROUTE = "admin/logs"

/**
 * Screens that have a route but are not built yet, with their mockup titles.
 * When you build one, remove it here and register it in [AppNavigation]. Add route arguments then too.
 */
private val PLACEHOLDER_SCREENS = mapOf(
    QR_SCAN_ROUTE to "Scan QR code",
    LOTTERIES_ROUTE to "My lotteries",
    INBOX_ROUTE to "Notifications",
    BOOKING_OFFER_ROUTE to "Choose campsite & dates",
    RESERVATION_CONFIRMATION_ROUTE to "Reservation confirmation",
    BOOKINGS_ROUTE to "My reservations",
    RESERVATION_DETAILS_ROUTE to "Reservation details",
    RATE_STAY_ROUTE to "Rate your stay",
    ACCESSIBILITY_ROUTE to "Accessibility settings",
    OWNER_DASHBOARD_ROUTE to "Owner dashboard",
    OWNER_CAMPSITE_EDIT_ROUTE to "Add / edit campsite",
    OWNER_CAMPSITE_ROUTE to "Owner campsite details",
    LOTTERY_EDIT_ROUTE to "Create / edit lottery",
    LOTTERY_MANAGEMENT_ROUTE to "Lottery management",
    OWNER_RESERVATIONS_ROUTE to "Owner reservations",
    OWNER_NOTIFICATIONS_ROUTE to "Owner notifications",
    ADMIN_DASHBOARD_ROUTE to "Admin dashboard",
    ADMIN_LOGS_ROUTE to "Admin logs",
)

/**
 * A bottom-bar destination.
 * @property route The tab's route.
 * @property label The text under the icon.
 * @property icon The tab's drawable icon.
 */
private data class Tab(val route: String, val label: String, @DrawableRes val icon: Int)

/** Bottom-bar tabs in mockup order. */
private val TABS = listOf(
    Tab(HOME_ROUTE, "Explore", R.drawable.ic_nav_explore),
    Tab(LOTTERIES_ROUTE, "Lotteries", R.drawable.ic_nav_lotteries),
    Tab(INBOX_ROUTE, "Inbox", R.drawable.ic_nav_inbox),
    Tab(BOOKINGS_ROUTE, "Bookings", R.drawable.ic_nav_bookings),
    Tab(PROFILE_ROUTE, "Profile", R.drawable.ic_nav_profile),
)

/**
 * Shows onboarding until the account is ready, then routes between the app's screens.
 *
 * Removes onboarding from the back stack so Back from Home leaves the app.
 * A restored screen also waits for the current process to finish checking the profile.
 *
 * @param controller Provides the setup state and handles Retry actions.
 * @param navController Moves between screens and remembers the back stack.
 * @param campsites Supplies active campsite listings for the home screen.
 * @param details Supplies the selected campsite and its photos.
 * @param profiles Reads and changes the user's profile.
 * @param welcome Remembers whether S01 was shown; null skips S01.
 */
@Composable
fun AppNavigation(
    controller: OnboardingController,
    navController: NavHostController = rememberNavController(),
    campsites: CampsiteRepository = FirebaseRepository.instance,
    details: CampsiteDetailsRepository = FirebaseRepository.instance,
    profiles: ProfileRepository = FirebaseProfileRepository.instance,
    welcome: WelcomePreferences? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val current by navController.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    Column {
        NavHost(navController = navController, startDestination = ONBOARDING_ROUTE, modifier = Modifier.weight(1f)) {
            composable(ONBOARDING_ROUTE) {
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(controller, lifecycleOwner, navController) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        controller.state.filterIsInstance<OnboardingState.Ready>().collect {
                            if (navController.currentDestination?.route == ONBOARDING_ROUTE) {
                                val next = if (welcome?.hasSeenWelcome() == false) WELCOME_ROUTE else HOME_ROUTE
                                navController.navigate(next) {
                                    popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                }
                OnboardingScreen(state, controller::retry)
            }
            composable(WELCOME_ROUTE) {
                RequireAccount(state, controller) {
                    WelcomeScreen(
                        onStart = {
                            welcome?.markWelcomeSeen()
                            navController.navigate(HOME_ROUTE) {
                                popUpTo(WELCOME_ROUTE) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onPolicy = { navController.navigate(POLICY_ROUTE) { launchSingleTop = true } },
                    )
                }
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
            composable(POLICY_ROUTE) {
                PolicyScreen { navController.popBackStack() }
            }
            composable(PROFILE_ROUTE) {
                RequireAccount(state, controller) {
                    val profile: ProfileController = viewModel(factory = viewModelFactory {
                        initializer { ProfileController(profiles) }
                    })
                    val settings by profile.state.collectAsStateWithLifecycle()
                    LaunchedEffect(settings.deleted) {
                        if (settings.deleted) {
                            // Clear every screen, then open a new device identity.
                            navController.navigate(ONBOARDING_ROUTE) { popUpTo(navController.graph.id) { inclusive = true } }
                            controller.restart()
                        }
                    }
                    ProfileScreen(
                        state = settings,
                        onNameChange = profile::setName,
                        onEmailChange = profile::setEmail,
                        onPhoneChange = profile::setPhone,
                        onSave = profile::save,
                        onNotificationsChange = profile::setNotificationsEnabled,
                        onPolicy = { navController.navigate(POLICY_ROUTE) { launchSingleTop = true } },
                        onDelete = profile::requestDelete,
                        onConfirmDelete = profile::confirmDelete,
                        onCancelDelete = profile::cancelDelete,
                        onRetry = profile::retry,
                    )
                }
            }
            PLACEHOLDER_SCREENS.forEach { (route, title) ->
                composable(route) {
                    RequireAccount(state, controller) {
                        PlaceholderScreen(title, if (TABS.any { it.route == route }) null else { { navController.popBackStack() } })
                    }
                }
            }
        }
        if (TABS.any { it.route == currentRoute }) {
            // The activity's Scaffold already pads for the system navigation bar.
            NavigationBar(windowInsets = WindowInsets(0)) {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(HOME_ROUTE) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }
}

/**
 * Shows [content] only after this process has checked the account, like a restored Home.
 * @param state The current setup state.
 * @param controller Handles Retry while setup is shown instead.
 * @param content The screen to show once the account is ready.
 */
@Composable
private fun RequireAccount(state: OnboardingState, controller: OnboardingController, content: @Composable () -> Unit) {
    if (state is OnboardingState.Ready) content() else OnboardingScreen(state, controller::retry)
}
