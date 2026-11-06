/*
 * AppNavigation.kt
 *
 * Purpose: Shared router: onboarding, S01 welcome, every screen route (S01 to S20), and the bottom
 *   bar.
 * Role: Controller in MVC; also the navigation skeleton every team member registers screens in.
 * Outstanding issues: Screens still listed in PLACEHOLDER_SCREENS await implementation.
 */
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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.example.legion_events.view.QrScanScreen
import com.example.legion_events.view.LotteryEditScreen
import com.example.legion_events.view.LotteryManagementScreen
import com.example.legion_events.view.OwnerLotteriesScreen
import com.example.legion_events.view.CampsiteDetailsScreen
import com.example.legion_events.view.CampsiteEditActions
import com.example.legion_events.view.CampsiteEditScreen
import com.example.legion_events.view.OwnerCampsiteScreen
import com.example.legion_events.view.OwnerDashboardScreen
import com.example.legion_events.view.OnboardingScreen
import com.example.legion_events.view.PlaceholderScreen
import com.example.legion_events.view.PolicyScreen
import com.example.legion_events.view.ProfileScreen
import com.example.legion_events.view.WelcomeScreen
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.FirebaseLotteryRepository
import com.example.legion_events.data.FirebaseProfileRepository
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.FirebaseOwnerCampsiteRepository
import com.example.legion_events.data.OwnerCampsiteRepository
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
/** S13 with an optional `campsiteId`: missing adds a campsite, present edits that one. */
internal const val OWNER_CAMPSITE_EDIT_PATTERN = "$OWNER_CAMPSITE_EDIT_ROUTE?campsiteId={campsiteId}"
/** S14: owner campsite details. */
internal const val OWNER_CAMPSITE_ROUTE = "owner/campsite"
/** S14 for one campsite; only its document ID is saved in the navigation arguments. */
internal const val OWNER_CAMPSITE_PATTERN = "$OWNER_CAMPSITE_ROUTE/{campsiteId}"
/** S15: create or edit a lottery. */
internal const val LOTTERY_EDIT_ROUTE = "owner/lottery_edit"
/** S15 with an optional `lotteryId`: missing creates a lottery, present edits that one. */
internal const val LOTTERY_EDIT_PATTERN = "$LOTTERY_EDIT_ROUTE?lotteryId={lotteryId}"
/** Owner Lotteries tab: the owner's lotteries, leading to S15 and S16. */
internal const val LOTTERY_MANAGEMENT_ROUTE = "owner/lottery"
/** S16 for one lottery; only its document ID is saved in the navigation arguments. */
internal const val LOTTERY_DETAILS_PATTERN = "$LOTTERY_MANAGEMENT_ROUTE/{lotteryId}"
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
    LOTTERIES_ROUTE to "My lotteries",
    INBOX_ROUTE to "Notifications",
    BOOKING_OFFER_ROUTE to "Choose campsite & dates",
    RESERVATION_CONFIRMATION_ROUTE to "Reservation confirmation",
    BOOKINGS_ROUTE to "My reservations",
    RESERVATION_DETAILS_ROUTE to "Reservation details",
    RATE_STAY_ROUTE to "Rate your stay",
    ACCESSIBILITY_ROUTE to "Accessibility settings",
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

/** Owner bottom-bar tabs in mockup order, shown on S12, S14, and the owner tab screens. */
private val OWNER_TABS = listOf(
    Tab(OWNER_DASHBOARD_ROUTE, "Dashboard", R.drawable.ic_nav_dashboard),
    Tab(LOTTERY_MANAGEMENT_ROUTE, "Lotteries", R.drawable.ic_nav_lotteries),
    Tab(OWNER_RESERVATIONS_ROUTE, "Reservations", R.drawable.ic_nav_bookings),
    Tab(OWNER_NOTIFICATIONS_ROUTE, "Notices", R.drawable.ic_nav_inbox),
)

/**
 * Opens S14 for a campsite.
 * @param campsiteId The campsite document ID.
 * @return The route to navigate to.
 */
private fun ownerCampsiteRoute(campsiteId: String) = "$OWNER_CAMPSITE_ROUTE/${Uri.encode(campsiteId)}"

/**
 * Opens S15.
 * @param lotteryId The lottery to edit, or null to create one.
 * @return The route to navigate to.
 */
private fun lotteryEditRoute(lotteryId: String?) =
    if (lotteryId == null) LOTTERY_EDIT_ROUTE else "$LOTTERY_EDIT_ROUTE?lotteryId=${Uri.encode(lotteryId)}"

/**
 * Opens S16 for a lottery.
 * @param lotteryId The lottery document ID.
 * @return The route to navigate to.
 */
private fun lotteryDetailsRoute(lotteryId: String) = "$LOTTERY_MANAGEMENT_ROUTE/${Uri.encode(lotteryId)}"

/**
 * Opens S13.
 * @param campsiteId The campsite to edit, or null to add one.
 * @return The route to navigate to.
 */
private fun campsiteEditRoute(campsiteId: String?) =
    if (campsiteId == null) OWNER_CAMPSITE_EDIT_ROUTE else "$OWNER_CAMPSITE_EDIT_ROUTE?campsiteId=${Uri.encode(campsiteId)}"

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
 * @param lotteries Reads and saves lotteries.
 * @param owners Reads and saves the signed-in user's campsites for S12 to S14.
 */
@Composable
fun AppNavigation(
    controller: OnboardingController,
    navController: NavHostController = rememberNavController(),
    campsites: CampsiteRepository = FirebaseRepository.instance,
    details: CampsiteDetailsRepository = FirebaseRepository.instance,
    profiles: ProfileRepository = FirebaseProfileRepository.instance,
    welcome: WelcomePreferences? = null,
    lotteries: LotteryRepository = FirebaseLotteryRepository.instance,
    owners: OwnerCampsiteRepository = FirebaseOwnerCampsiteRepository.instance,
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
                    HomeScreen(listings, search::search, search::filter, search::retry,
                        onScanQr = { navController.navigate(QR_SCAN_ROUTE) { launchSingleTop = true } }) { campsiteId ->
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
            composable(QR_SCAN_ROUTE) {
                RequireAccount(state, controller) {
                    val scan: QrScanController = viewModel(factory = viewModelFactory {
                        initializer { QrScanController(createSavedStateHandle()) }
                    })
                    val scanning by scan.state.collectAsStateWithLifecycle()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(scan, lifecycleOwner, navController) {
                        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            scan.state.collect { result ->
                                if (navController.currentDestination?.route == QR_SCAN_ROUTE) {
                                    if (result.status == QrScanStatus.FOUND) {
                                        navController.navigate("campsite/${Uri.encode(checkNotNull(result.campsiteId))}") {
                                            popUpTo(QR_SCAN_ROUTE) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    } else if (result.status == QrScanStatus.CANCELLED) navController.popBackStack()
                                }
                            }
                        }
                    }
                    QrScanScreen(scanning, scan::scanStarted, scan::scanResult, scan::cameraFailed,
                        scan::retry, { navController.popBackStack() })
                }
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
                        onOwnerDashboard = { navController.navigate(OWNER_DASHBOARD_ROUTE) { launchSingleTop = true } },
                        onAdminDashboard = { navController.navigate(ADMIN_DASHBOARD_ROUTE) { launchSingleTop = true } },
                        onPolicy = { navController.navigate(POLICY_ROUTE) { launchSingleTop = true } },
                        onDelete = profile::requestDelete,
                        onConfirmDelete = profile::confirmDelete,
                        onCancelDelete = profile::cancelDelete,
                        onRetry = profile::retry,
                    )
                }
            }
            composable(OWNER_DASHBOARD_ROUTE) {
                RequireAccount(state, controller) {
                    val dashboard: OwnerDashboardController = viewModel(factory = viewModelFactory {
                        initializer { OwnerDashboardController(owners) }
                    })
                    val listings by dashboard.state.collectAsStateWithLifecycle()
                    // Reload on every return so campsites added or edited in S13 appear.
                    LifecycleResumeEffect(dashboard) {
                        dashboard.refresh()
                        onPauseOrDispose {}
                    }
                    OwnerDashboardScreen(
                        state = listings,
                        onBack = { navController.popBackStack() },
                        onRetry = dashboard::refresh,
                        onCampsiteClick = { id -> navController.navigate(ownerCampsiteRoute(id)) { launchSingleTop = true } },
                        onAddCampsite = { navController.navigate(campsiteEditRoute(null)) { launchSingleTop = true } },
                    )
                }
            }
            composable(
                OWNER_CAMPSITE_EDIT_PATTERN,
                arguments = listOf(navArgument("campsiteId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                RequireAccount(state, controller) {
                    val edit: CampsiteEditController = viewModel(factory = viewModelFactory {
                        initializer { CampsiteEditController(entry.arguments?.getString("campsiteId"), owners) }
                    })
                    val form by edit.state.collectAsStateWithLifecycle()
                    val editingExisting = entry.arguments?.getString("campsiteId") != null
                    LaunchedEffect(form.savedCampsiteId) {
                        val saved = form.savedCampsiteId ?: return@LaunchedEffect
                        if (editingExisting) {
                            // S14 is below this form and reloads when it resumes.
                            navController.popBackStack()
                        } else {
                            // Replace the form with S14 so Back returns to S12, not the saved form.
                            navController.navigate(ownerCampsiteRoute(saved)) {
                                popUpTo(OWNER_CAMPSITE_EDIT_PATTERN) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    CampsiteEditScreen(form, CampsiteEditActions(
                        onBack = { navController.popBackStack() },
                        onRetry = edit::retry,
                        onName = edit::setName,
                        onLocationName = edit::setLocationName,
                        onDescription = edit::setDescription,
                        onToggleAmenity = edit::toggleAmenity,
                        onTagInput = edit::setTagInput,
                        onAddTag = edit::addTag,
                        onRemoveTag = edit::removeTag,
                        onMaxGuests = edit::setMaxGuests,
                        onPrice = edit::setPrice,
                        onLatitude = edit::setLatitude,
                        onLongitude = edit::setLongitude,
                        onLocating = edit::startLocating,
                        onCurrentLocation = edit::setCurrentLocation,
                        onLocationFailed = edit::locationFailed,
                        onRules = edit::setRules,
                        onCancellationPolicy = edit::setCancellationPolicy,
                        onOpenFrom = edit::setOpenFrom,
                        onOpenUntil = edit::setOpenUntil,
                        onPhotoPicked = edit::startPhoto,
                        onPhoto = edit::setPhoto,
                        onRemovePhoto = edit::removePhoto,
                        onSave = edit::save,
                    ))
                }
            }
            composable(OWNER_CAMPSITE_PATTERN, arguments = listOf(navArgument("campsiteId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) {
                    val campsiteId = checkNotNull(entry.arguments?.getString("campsiteId"))
                    val owned: OwnerCampsiteController = viewModel(factory = viewModelFactory {
                        initializer { OwnerCampsiteController(campsiteId, owners) }
                    })
                    val information by owned.state.collectAsStateWithLifecycle()
                    // Reload on every return so edits saved in S13 appear.
                    LifecycleResumeEffect(owned) {
                        owned.refresh()
                        onPauseOrDispose {}
                    }
                    OwnerCampsiteScreen(
                        state = information,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(campsiteEditRoute(campsiteId)) { launchSingleTop = true } },
                        onRetry = owned::refresh,
                        onGenerateQr = owned::generateQr,
                    )
                }
            }
            composable(
                LOTTERY_EDIT_PATTERN,
                arguments = listOf(navArgument("lotteryId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                RequireAccount(state, controller) {
                    val edit: LotteryEditController = viewModel(factory = viewModelFactory {
                        initializer { LotteryEditController(entry.arguments?.getString("lotteryId"), lotteries, owners::getMyCampsites) }
                    })
                    val form by edit.state.collectAsStateWithLifecycle()
                    val editingExisting = entry.arguments?.getString("lotteryId") != null
                    LaunchedEffect(form.savedLotteryId) {
                        val saved = form.savedLotteryId ?: return@LaunchedEffect
                        if (editingExisting) {
                            // S16 is below this form and reloads when it resumes.
                            navController.popBackStack()
                        } else {
                            // Replace S15 with S16 so Back returns to the Lotteries tab, not the saved form.
                            navController.navigate(lotteryDetailsRoute(saved)) {
                                popUpTo(LOTTERY_EDIT_PATTERN) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    LotteryEditScreen(
                        state = form,
                        onBack = { navController.popBackStack() },
                        onRetry = edit::retry,
                        onToggleCampsite = edit::toggleCampsite,
                        onRegistrationStart = edit::setRegistrationStart,
                        onRegistrationEnd = edit::setRegistrationEnd,
                        onBookingStart = edit::setBookingStart,
                        onBookingEnd = edit::setBookingEnd,
                        onWinnersChange = edit::setWinnerCount,
                        onLimitChange = edit::setWaitlistLimit,
                        onSave = edit::save,
                    )
                }
            }
            composable(LOTTERY_MANAGEMENT_ROUTE) {
                RequireAccount(state, controller) {
                    val list: OwnerLotteriesController = viewModel(factory = viewModelFactory {
                        initializer { OwnerLotteriesController(lotteries, owners::getMyCampsites) }
                    })
                    val listings by list.state.collectAsStateWithLifecycle()
                    // Reload on every return so lotteries created or edited in S15 appear.
                    LifecycleResumeEffect(list) {
                        list.refresh()
                        onPauseOrDispose {}
                    }
                    OwnerLotteriesScreen(
                        state = listings,
                        onRetry = list::refresh,
                        onLotteryClick = { id -> navController.navigate(lotteryDetailsRoute(id)) { launchSingleTop = true } },
                        onCreateLottery = { navController.navigate(lotteryEditRoute(null)) { launchSingleTop = true } },
                    )
                }
            }
            composable(LOTTERY_DETAILS_PATTERN, arguments = listOf(navArgument("lotteryId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) {
                    val lotteryId = checkNotNull(entry.arguments?.getString("lotteryId"))
                    val manage: LotteryManagementController = viewModel(factory = viewModelFactory {
                        initializer { LotteryManagementController(lotteryId, lotteries, owners::getMyCampsites) }
                    })
                    val information by manage.state.collectAsStateWithLifecycle()
                    // Reload on every return so edits saved in S15 appear.
                    LifecycleResumeEffect(manage) {
                        manage.refresh()
                        onPauseOrDispose {}
                    }
                    LotteryManagementScreen(
                        state = information,
                        onBack = { navController.popBackStack() },
                        onRetry = manage::refresh,
                        onEdit = { navController.navigate(lotteryEditRoute(lotteryId)) { launchSingleTop = true } },
                        onTab = manage::selectTab,
                    )
                }
            }
            PLACEHOLDER_SCREENS.forEach { (route, title) ->
                composable(route) {
                    RequireAccount(state, controller) {
                        val tab = (TABS + OWNER_TABS).any { it.route == route }
                        PlaceholderScreen(title, if (tab) null else { { navController.popBackStack() } })
                    }
                }
            }
        }
        if (OWNER_TABS.any { it.route == currentRoute } || currentRoute == OWNER_CAMPSITE_PATTERN ||
            currentRoute == LOTTERY_DETAILS_PATTERN) {
            NavigationBar(windowInsets = WindowInsets(0)) {
                OWNER_TABS.forEach { tab ->
                    NavigationBarItem(
                        // S14 belongs to the Dashboard tab.
                        selected = currentRoute == tab.route ||
                            (tab.route == OWNER_DASHBOARD_ROUTE && currentRoute == OWNER_CAMPSITE_PATTERN) ||
                            (tab.route == LOTTERY_MANAGEMENT_ROUTE && currentRoute == LOTTERY_DETAILS_PATTERN),
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(OWNER_DASHBOARD_ROUTE) { saveState = true }
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
