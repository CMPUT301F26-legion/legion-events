/*
 * AppNavigation.kt
 *
 * Purpose: Shared router: onboarding, S01 welcome, every screen route (S01 to S20), and the bottom
 *   bar.
 * Role: Controller in MVC; also the navigation skeleton every team member registers screens in.
 * Outstanding issues: Screens still listed in PLACEHOLDER_SCREENS await implementation.
 */
package com.example.legion_events.controller

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.example.legion_events.view.InboxScreen
import com.example.legion_events.view.QrScanScreen
import com.example.legion_events.view.ReservationsScreen
import com.example.legion_events.view.ReservationDetailsScreen
import com.example.legion_events.view.RateStayScreen
import com.example.legion_events.data.RatingRepository
import com.example.legion_events.data.FirebaseRatingRepository
import com.example.legion_events.view.ReservationConfirmationScreen
import com.example.legion_events.view.LotteryEditScreen
import com.example.legion_events.view.LotteriesScreen
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
import com.example.legion_events.view.ChatScreen
import com.example.legion_events.view.MessagesScreen
import com.example.legion_events.data.MessageRepository
import com.example.legion_events.data.FirebaseMessageRepository
import com.example.legion_events.data.loadRelatedUsers
import com.example.legion_events.data.encodePhoto
import com.example.legion_events.view.WelcomeScreen
import com.example.legion_events.view.BookingOfferScreen
import com.example.legion_events.view.BookingOffersScreen
import com.example.legion_events.data.CampsiteRepository
import com.example.legion_events.data.CampsiteDetailsRepository
import com.example.legion_events.data.FirebaseLotteryRepository
import com.example.legion_events.data.FirebaseNotificationRepository
import com.example.legion_events.data.NotificationRepository
import com.example.legion_events.data.FirebaseOfferRepository
import com.example.legion_events.data.FirebaseProfileRepository
import com.example.legion_events.data.LotteryRepository
import com.example.legion_events.data.FirebaseOwnerCampsiteRepository
import com.example.legion_events.data.OwnerCampsiteRepository
import com.example.legion_events.data.OfferRepository
import com.example.legion_events.data.FirebaseRepository
import com.example.legion_events.data.ProfileRepository
import com.example.legion_events.data.WelcomePreferences
import com.example.legion_events.data.FirebaseOwnerNoticeRepository
import com.example.legion_events.data.FirebaseWaitlistRepository
import com.example.legion_events.data.OwnerNoticeRepository
import com.example.legion_events.data.OwnerReservationRepository
import com.example.legion_events.data.FirebaseOwnerReservationRepository
import com.example.legion_events.data.WaitlistRepository
import com.example.legion_events.data.ReservationRepository
import com.example.legion_events.data.writeReservationCsv
import com.example.legion_events.data.FirebaseReservationRepository
import com.example.legion_events.view.CampsiteLotterySection
import com.example.legion_events.view.OwnerNoticesScreen
import com.example.legion_events.view.OwnerReservationsActions
import com.example.legion_events.view.OwnerReservationsScreen
import com.example.legion_events.view.AdminCampsiteScreen
import com.example.legion_events.view.AdminCampsitesScreen
import com.example.legion_events.view.AdminImagesScreen
import com.example.legion_events.view.AdminLogsScreen
import com.example.legion_events.view.AdminProfilesScreen
import com.example.legion_events.data.AdminImageRepository
import com.example.legion_events.data.AdminLogRepository
import com.example.legion_events.data.AdminRepository
import com.example.legion_events.data.FirebaseAdminImageRepository
import com.example.legion_events.data.FirebaseAdminLogRepository
import com.example.legion_events.data.FirebaseAdminRepository
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
/** Private conversations are separate destinations from notices and lottery notifications. */
internal const val MESSAGES_ROUTE = "messages"
internal const val CHAT_PATTERN = "message/{conversationId}"
internal const val CONTACT_OWNER_PATTERN = "message/new/{campsiteId}"

/** Builds a private thread route without exposing participant IDs as caller-controlled arguments. */
internal fun chatRoute(id: String): String {
    require(id.isNotBlank() && '/' !in id)
    return "message/${Uri.encode(id)}"
}

/** Starts or reopens the signed-in entrant's stable conversation for a campsite. */
internal fun contactOwnerRoute(id: String): String {
    require(id.isNotBlank() && '/' !in id)
    return "message/new/${Uri.encode(id)}"
}
/** S08 entry: list owned offers; campsite/date selection remains a later booking feature. */
internal const val BOOKING_OFFER_ROUTE = "booking_offer"
/** One owned offer, opened by the list or a winning notification. */
internal const val BOOKING_OFFER_DETAILS_ROUTE = "booking_offer/{offerId}"

/**
 * Builds the shared destination for a winning offer using only its document ID.
 * @param offerId A single offers document ID from the list or winning notification.
 * @return Encoded internal navigation route.
 */
internal fun bookingOfferRoute(offerId: String): String {
    require(offerId.isNotBlank() && '/' !in offerId) { "A single offer document ID is required." }
    return "booking_offer/${Uri.encode(offerId)}"
}

/** S09: reservation confirmation. */
internal const val RESERVATION_CONFIRMATION_ROUTE = "reservation_confirmation"
/** S09 for one saved booking; the repository checks ownership and confirmation status. */
internal const val RESERVATION_CONFIRMATION_PATTERN = "$RESERVATION_CONFIRMATION_ROUTE/{reservationId}"

/** Builds S09's route from the saved reservation ID; booking creation can use this after success. */
internal fun reservationConfirmationRoute(reservationId: String): String {
    require(reservationId.isNotBlank() && '/' !in reservationId) { "A single reservation ID is required." }
    return "$RESERVATION_CONFIRMATION_ROUTE/${Uri.encode(reservationId)}"
}
/** S10: the entrant's reservations. */
internal const val BOOKINGS_ROUTE = "bookings"
/** S10b: one reservation's details. */
internal const val RESERVATION_DETAILS_ROUTE = "reservation_details"
/** S10b for one owned booking; only its document ID is saved in navigation state. */
internal const val RESERVATION_DETAILS_PATTERN = "$RESERVATION_DETAILS_ROUTE/{reservationId}"

/** Builds a details route from one reservation ID, preserving spaces and URI punctuation. */
internal fun reservationDetailsRoute(reservationId: String): String {
    require(reservationId.isNotBlank() && '/' !in reservationId) { "A single reservation ID is required." }
    return "$RESERVATION_DETAILS_ROUTE/${Uri.encode(reservationId)}"
}

/** S10c: rate a completed stay. */
internal const val RATE_STAY_ROUTE = "rate_stay"
/** S10c receives only a booking ID; the repository checks the UID, campsite, and checkout again. */
internal const val RATE_STAY_PATTERN = "$RATE_STAY_ROUTE/{reservationId}"

/** Builds the rating route while preserving spaces and URI punctuation in the saved booking ID. */
internal fun rateStayRoute(reservationId: String): String {
    require(reservationId.isNotBlank() && '/' !in reservationId) { "A single reservation ID is required." }
    return "$RATE_STAY_ROUTE/${Uri.encode(reservationId)}"
}
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
/** S19: admin dashboard, opening on its Campsites tab. */
internal const val ADMIN_DASHBOARD_ROUTE = "admin"
/** S19 Profiles tab. */
internal const val ADMIN_PROFILES_ROUTE = "admin/profiles"
/** S19 campsite details for one campsite; only its document ID is saved in the navigation arguments. */
internal const val ADMIN_CAMPSITE_PATTERN = "admin/campsite/{campsiteId}"
/** S19 Images tab: every uploaded image. */
internal const val ADMIN_IMAGES_ROUTE = "admin/images"
/** S20: admin logs. */
internal const val ADMIN_LOGS_ROUTE = "admin/logs"

/**
 * Screens that have a route but are not built yet, with their mockup titles.
 * When you build one, remove it here and register it in [AppNavigation]. Add route arguments then too.
 */
private val PLACEHOLDER_SCREENS = mapOf(
    ACCESSIBILITY_ROUTE to "Accessibility settings",
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

/** Admin bottom-bar tabs in mockup order, shown on S19 and S20. */
private val ADMIN_TABS = listOf(
    Tab(ADMIN_DASHBOARD_ROUTE, "Campsites", R.drawable.ic_nav_explore),
    Tab(ADMIN_PROFILES_ROUTE, "Profiles", R.drawable.ic_nav_profile),
    Tab(ADMIN_IMAGES_ROUTE, "Images", R.drawable.ic_nav_images),
    Tab(ADMIN_LOGS_ROUTE, "Logs", R.drawable.ic_nav_inbox),
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
 * @param offers Reads and declines the authenticated user's offers; tests inject a fake.
 * @param notifications Observes the authenticated user's inbox.
 * @param localDrawRevision Refreshes visible owner results after a foreground draw commits.
 * @param waitlists Joins and leaves lottery waitlists for S04 and S06.
 * @param notices Sends owner notices on S18 and delivers them to the inbox.
 * @param inboxRecipient Account UID from a pending lottery push tap.
 * @param onInboxRequestConsumed Clears a handled or invalid push tap.
 * @param onEnablePush Requests Android's notification permission when available.
 * @param reservations Reads the authenticated entrant's saved bookings.
 * @param admin Reads and moderates every campsite and profile on S19.
 * @param ownerReservations Reads and rejects bookings at the owner's campsites on S17.
 * @param ratings Loads and saves the entrant's score from a completed stay on S10c.
 * @param images Reads and removes every uploaded image on the S19 Images tab.
 * @param logs Reads every notification and owner-to-entrant message on S20.
 * @param messages Supplies private chat, independently of announcements.
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
    offers: OfferRepository = FirebaseOfferRepository.instance,
    notifications: NotificationRepository = FirebaseNotificationRepository.instance,
    localDrawRevision: Int = 0,
    waitlists: WaitlistRepository = FirebaseWaitlistRepository.instance,
    notices: OwnerNoticeRepository = FirebaseOwnerNoticeRepository.instance,
    inboxRecipient: String? = null,
    onInboxRequestConsumed: () -> Unit = {},
    onEnablePush: (() -> Unit)? = null,
    reservations: ReservationRepository = FirebaseReservationRepository.instance,
    admin: AdminRepository = FirebaseAdminRepository.instance,
    ownerReservations: OwnerReservationRepository = FirebaseOwnerReservationRepository.instance,
    ratings: RatingRepository = FirebaseRatingRepository.instance,
    images: AdminImageRepository = FirebaseAdminImageRepository.instance,
    logs: AdminLogRepository = FirebaseAdminLogRepository.instance,
    messages: MessageRepository = FirebaseMessageRepository.instance,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val current by navController.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    val userId = (state as? OnboardingState.Ready)?.profile?.uid
    LaunchedEffect(inboxRecipient, userId, currentRoute) {
        if (inboxRecipient == null || userId == null || currentRoute == null) return@LaunchedEffect
        if (inboxRecipient != userId) {
            onInboxRequestConsumed()
        } else if (currentRoute != ONBOARDING_ROUTE && welcome?.hasSeenWelcome() != false) {
            navController.navigate(INBOX_ROUTE) { launchSingleTop = true }
            onInboxRequestConsumed()
        }
    }
    // Detail screens keep their parent tab selected, including direct links from other screens.
    val entrantTab = when (currentRoute) {
        MESSAGES_ROUTE, CHAT_PATTERN, CONTACT_OWNER_PATTERN -> INBOX_ROUTE
        BOOKING_OFFER_ROUTE, BOOKING_OFFER_DETAILS_ROUTE -> LOTTERIES_ROUTE
        RESERVATION_DETAILS_PATTERN, RESERVATION_CONFIRMATION_PATTERN, RATE_STAY_PATTERN -> BOOKINGS_ROUTE
        else -> currentRoute
    }
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
                    val join: CampsiteLotteryController = viewModel(factory = viewModelFactory {
                        initializer { CampsiteLotteryController(checkNotNull(entry.arguments?.getString("campsiteId")), waitlists) }
                    })
                    val joining by join.state.collectAsStateWithLifecycle()
                    CampsiteDetailsScreen(information, { navController.popBackStack() }, detail::retry, detail::retryPhotos,
                        onMessageOwner = information.campsite?.takeIf { it.ownerId != userId }?.let { site ->
                            { navController.navigate(contactOwnerRoute(site.id)) { launchSingleTop = true } }
                        }) {
                        CampsiteLotterySection(joining, join::join, join::retry) {
                            navController.navigate(LOTTERIES_ROUTE) {
                                popUpTo(HOME_ROUTE) { saveState = true }
                                launchSingleTop = true
                            }
                        }
                    }
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
            composable(LOTTERIES_ROUTE) {
                RequireAccount(state, controller) {
                    val mine: MyLotteriesController = viewModel(factory = viewModelFactory {
                        initializer { MyLotteriesController(waitlists) }
                    })
                    val entries by mine.state.collectAsStateWithLifecycle()
                    // Reload on every return so lotteries joined on S04 appear.
                    LifecycleResumeEffect(mine) {
                        mine.retry()
                        onPauseOrDispose {}
                    }
                    LotteriesScreen(entries, mine::canLeave, mine::requestLeave, mine::cancelLeave,
                        mine::confirmLeave, mine::retry) {
                        navController.navigate(BOOKING_OFFER_ROUTE) { launchSingleTop = true }
                    }
                }
            }
            composable(BOOKING_OFFER_ROUTE) {
                RequireAccount(state, controller) {
                    val list: BookingOffersController = viewModel(factory = viewModelFactory {
                        initializer { BookingOffersController(offers) }
                    })
                    val information by list.state.collectAsStateWithLifecycle()
                    // Returning from a decline reloads the list with its saved status.
                    LifecycleResumeEffect(list) {
                        list.retry()
                        onPauseOrDispose {}
                    }
                    BookingOffersScreen(information, { navController.popBackStack() }, list::retry) { offerId ->
                        navController.navigate(bookingOfferRoute(offerId)) { launchSingleTop = true }
                    }
                }
            }
            composable(
                BOOKING_OFFER_DETAILS_ROUTE,
                arguments = listOf(navArgument("offerId") { type = NavType.StringType }),
            ) { entry ->
                RequireAccount(state, controller) {
                    val offer: BookingOfferController = viewModel(factory = viewModelFactory {
                        initializer { BookingOfferController(checkNotNull(entry.arguments?.getString("offerId")), offers) }
                    })
                    val information by offer.state.collectAsStateWithLifecycle()
                    BookingOfferScreen(information, { navController.popBackStack() }, offer::retry,
                        offer::requestDecline, offer::confirmDecline, offer::cancelDecline)
                }
            }
            composable(BOOKINGS_ROUTE) {
                RequireAccount(state, controller) {
                    val list: ReservationsController = viewModel(factory = viewModelFactory {
                        initializer { ReservationsController(reservations, createSavedStateHandle()) }
                    })
                    val bookings by list.state.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(list) {
                        list.refresh()
                        onPauseOrDispose {}
                    }
                    ReservationsScreen(bookings, list::refresh, list::selectTab) { id ->
                        navController.navigate(reservationDetailsRoute(id)) { launchSingleTop = true }
                    }
                }
            }
            composable(RESERVATION_DETAILS_PATTERN,
                arguments = listOf(navArgument("reservationId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) {
                    val detail: ReservationDetailsController = viewModel(factory = viewModelFactory {
                        initializer { ReservationDetailsController(checkNotNull(entry.arguments?.getString("reservationId")), reservations) }
                    })
                    val booking by detail.state.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(detail) { detail.resume(); onPauseOrDispose { detail.pause() } }
                    val download = rememberReservationDownload(detail, booking)
                    ReservationDetailsScreen(booking, { navController.popBackStack() }, detail::retry,
                        onCampsite = { campsiteId ->
                            navController.navigate("campsite/${Uri.encode(campsiteId)}") { launchSingleTop = true }
                        },
                        onConfirmation = {
                            navController.navigate(reservationConfirmationRoute(checkNotNull(booking.entry).reservation.id)) {
                                launchSingleTop = true
                            }
                        }, onDownload = download, onCancel = detail::requestCancel,
                        onDismissCancellation = detail::dismissCancellation, onConfirmCancellation = detail::confirmCancellation,
                        onRate = { navController.navigate(rateStayRoute(checkNotNull(booking.entry).reservation.id)) { launchSingleTop = true } })
                }
            }
            composable(RESERVATION_CONFIRMATION_PATTERN,
                arguments = listOf(navArgument("reservationId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) {
                    val reservationId = checkNotNull(entry.arguments?.getString("reservationId"))
                    val confirmation: ReservationDetailsController = viewModel(factory = viewModelFactory {
                        initializer { ReservationDetailsController(reservationId, reservations) }
                    })
                    val booking by confirmation.state.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(confirmation) { confirmation.resume(); onPauseOrDispose { confirmation.pause() } }
                    val download = rememberReservationDownload(confirmation, booking)
                    ReservationConfirmationScreen(booking, { navController.popBackStack() }, confirmation::retry, download,
                        onReservation = {
                            navController.navigate(reservationDetailsRoute(reservationId)) {
                                popUpTo(RESERVATION_CONFIRMATION_PATTERN) { inclusive = true }
                                launchSingleTop = true
                            }
                        })
                }
            }
            composable(RATE_STAY_PATTERN,
                arguments = listOf(navArgument("reservationId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) {
                    val rate: RateStayController = viewModel(factory = viewModelFactory {
                        initializer { RateStayController(checkNotNull(entry.arguments?.getString("reservationId")), ratings, createSavedStateHandle()) }
                    })
                    val rating by rate.state.collectAsStateWithLifecycle()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val context = LocalContext.current.applicationContext
                    LaunchedEffect(rate, lifecycleOwner) {
                        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            rate.state.collect { result ->
                                if (result.saved && navController.currentDestination?.route == RATE_STAY_PATTERN) {
                                    Toast.makeText(context, "Rating saved", Toast.LENGTH_SHORT).show()
                                    if (!navController.popBackStack(BOOKINGS_ROUTE, false)) {
                                        navController.navigate(BOOKINGS_ROUTE) {
                                            popUpTo(RATE_STAY_PATTERN) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    RateStayScreen(rating, { navController.popBackStack() }, rate::retry, rate::selectScore, rate::submit)
                }
            }
            composable(PROFILE_ROUTE) {
                RequireAccount(state, controller) {
                    val profile: ProfileController = viewModel(factory = viewModelFactory {
                        initializer { ProfileController(profiles) }
                    })
                    val settings by profile.state.collectAsStateWithLifecycle()
                    val image: ProfilePhotoController = viewModel(factory = viewModelFactory { initializer { ProfilePhotoController(profiles) } })
                    val photo by image.state.collectAsStateWithLifecycle()
                    val pickPhoto = rememberProfileImagePicker(image, photo)
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
                        photo = photo, onPickPhoto = pickPhoto, onRemovePhoto = image::removePhoto, onRetryPhoto = image::retry, onReloadPhoto = image::load,
                        onMessages = { navController.navigate(MESSAGES_ROUTE) { launchSingleTop = true } },
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
                    LifecycleResumeEffect(dashboard, localDrawRevision) {
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
                    LifecycleResumeEffect(owned, localDrawRevision) {
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
                    LifecycleResumeEffect(list, localDrawRevision) {
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
                    LifecycleResumeEffect(manage, localDrawRevision) {
                        manage.refresh()
                        onPauseOrDispose {}
                    }
                    LotteryManagementScreen(
                        state = information,
                        onBack = { navController.popBackStack() },
                        onRetry = manage::refresh,
                        onEdit = { navController.navigate(lotteryEditRoute(lotteryId)) { launchSingleTop = true } },
                        onTab = manage::selectTab,
                        onEndRegistration = manage::requestEndRegistration,
                        onConfirmEnd = manage::confirmEndRegistration,
                        onCancelEnd = manage::cancelEndRegistration,
                    )
                }
            }
            composable(INBOX_ROUTE) {
                if (userId == null) {
                    OnboardingScreen(state, controller::retry)
                } else {
                    val inbox: InboxController = viewModel(key = "inbox-$userId", factory = viewModelFactory {
                        initializer { InboxController(userId, notifications, notices) }
                    })
                    val inboxState by inbox.state.collectAsStateWithLifecycle()
                    InboxScreen(
                        state = inboxState,
                        onRetry = inbox::retry,
                        onOffer = { id -> navController.navigate(bookingOfferRoute(id)) { launchSingleTop = true } },
                        onEnablePush = onEnablePush,
                        onReservation = { id -> navController.navigate(reservationConfirmationRoute(id)) { launchSingleTop = true } },
                        onMessages = { navController.navigate(MESSAGES_ROUTE) { launchSingleTop = true } },
                    )
                }
            }
            composable(MESSAGES_ROUTE) {
                RequireAccount(state, controller) {
                    val list: MessagesController = viewModel(key = "messages-$userId", factory = viewModelFactory {
                        initializer { MessagesController(messages) { loadRelatedUsers(checkNotNull(userId), messages, owners, ownerReservations) } }
                    })
                    val threads by list.state.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(list) { list.resume(); onPauseOrDispose { list.pause() } }
                    val openChat = { id: String -> navController.navigate(chatRoute(id)) { launchSingleTop = true } }
                    MessagesScreen(threads, { navController.popBackStack() }, list::resume, onConversation = openChat,
                        onStartChat = { user -> list.startPeerChat(user, openChat) })
                }
            }
            composable(CHAT_PATTERN, arguments = listOf(navArgument("conversationId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) { ChatDestination(navController, messages, conversationId = entry.arguments?.getString("conversationId")) }
            }
            composable(CONTACT_OWNER_PATTERN, arguments = listOf(navArgument("campsiteId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) { ChatDestination(navController, messages, campsiteId = entry.arguments?.getString("campsiteId")) }
            }
            composable(OWNER_NOTIFICATIONS_ROUTE) {
                RequireAccount(state, controller) {
                    val notes: OwnerNoticesController = viewModel(factory = viewModelFactory {
                        initializer { OwnerNoticesController(lotteries, owners::getMyCampsites, notices) }
                    })
                    val noticeState by notes.state.collectAsStateWithLifecycle()
                    // Reload on every return so new lotteries and entrants appear.
                    LifecycleResumeEffect(notes) {
                        notes.refresh()
                        onPauseOrDispose {}
                    }
                    OwnerNoticesScreen(noticeState, notes::selectLottery, notes::setMessage, notes::send, notes::refresh)
                }
            }
            composable(OWNER_RESERVATIONS_ROUTE) {
                RequireAccount(state, controller) {
                    val bookings: OwnerReservationsController = viewModel(factory = viewModelFactory {
                        initializer { OwnerReservationsController(owners::getMyCampsites, ownerReservations, createSavedStateHandle()) }
                    })
                    val bookingState by bookings.state.collectAsStateWithLifecycle()
                    // Reload on every return so campsites added in S13 and new bookings appear.
                    LifecycleResumeEffect(bookings) {
                        bookings.refresh()
                        onPauseOrDispose {}
                    }
                    OwnerReservationsScreen(bookingState, OwnerReservationsActions(
                        onRefresh = bookings::refresh,
                        onSelectCampsite = bookings::selectCampsite,
                        onTab = bookings::selectTab,
                        onMonth = bookings::showMonth,
                        onView = bookings::viewBooking,
                        onCloseView = bookings::closeBooking,
                        onAccept = bookings::accept,
                        onReject = bookings::requestReject,
                        onReason = bookings::setRejectReason,
                        onConfirmReject = bookings::confirmReject,
                        onCancelReject = bookings::cancelReject,
                    ))
                }
            }
            composable(ADMIN_DASHBOARD_ROUTE) {
                RequireAccount(state, controller) {
                    val list: AdminCampsitesController = viewModel(factory = viewModelFactory {
                        initializer { AdminCampsitesController(admin) }
                    })
                    val listings by list.state.collectAsStateWithLifecycle()
                    // Reload on every return so a listing removed in its details shows as removed.
                    LifecycleResumeEffect(list) {
                        list.refresh()
                        onPauseOrDispose {}
                    }
                    AdminCampsitesScreen(listings, { navController.popBackStack() }, list::setQuery, list::refresh) { id ->
                        navController.navigate("admin/campsite/${Uri.encode(id)}") { launchSingleTop = true }
                    }
                }
            }
            composable(ADMIN_CAMPSITE_PATTERN, arguments = listOf(navArgument("campsiteId") { type = NavType.StringType })) { entry ->
                RequireAccount(state, controller) {
                    val detail: AdminCampsiteController = viewModel(factory = viewModelFactory {
                        initializer { AdminCampsiteController(checkNotNull(entry.arguments?.getString("campsiteId")), admin, details) }
                    })
                    val information by detail.state.collectAsStateWithLifecycle()
                    AdminCampsiteScreen(information, { navController.popBackStack() }, detail::refresh,
                        detail::requestRemove, detail::confirmRemove, detail::cancelRemove)
                }
            }
            composable(ADMIN_PROFILES_ROUTE) {
                RequireAccount(state, controller) {
                    val people: AdminProfilesController = viewModel(factory = viewModelFactory {
                        initializer { AdminProfilesController(admin) }
                    })
                    val profileState by people.state.collectAsStateWithLifecycle()
                    AdminProfilesScreen(profileState, { navController.popBackStack() }, people::setQuery, people::refresh,
                        people::requestRemoveProfile, people::requestRemoveOwner, people::confirmRemove, people::cancelRemove)
                }
            }
            composable(ADMIN_IMAGES_ROUTE) {
                RequireAccount(state, controller) {
                    val gallery: AdminImagesController = viewModel(factory = viewModelFactory {
                        initializer { AdminImagesController(images, admin) }
                    })
                    LifecycleResumeEffect(gallery) {
                        gallery.refresh()
                        onPauseOrDispose { }
                    }
                    val imageState by gallery.state.collectAsStateWithLifecycle()
                    AdminImagesScreen(imageState, { navController.popBackStack() }, gallery::refresh,
                        gallery::requestRemove, gallery::confirmRemove, gallery::cancelRemove)
                }
            }
            composable(ADMIN_LOGS_ROUTE) {
                RequireAccount(state, controller) {
                    val history: AdminLogsController = viewModel(factory = viewModelFactory {
                        initializer { AdminLogsController(logs, admin) }
                    })
                    LifecycleResumeEffect(history) {
                        history.refresh()
                        onPauseOrDispose { }
                    }
                    val logState by history.state.collectAsStateWithLifecycle()
                    AdminLogsScreen(logState, { navController.popBackStack() }, history::refresh, history::selectTab)
                }
            }
            PLACEHOLDER_SCREENS.forEach { (route, title) ->
                composable(route) {
                    RequireAccount(state, controller) {
                        val tab = (TABS + OWNER_TABS + ADMIN_TABS).any { it.route == route }
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
        if (ADMIN_TABS.any { it.route == currentRoute } || currentRoute == ADMIN_CAMPSITE_PATTERN) {
            NavigationBar(windowInsets = WindowInsets(0)) {
                ADMIN_TABS.forEach { tab ->
                    NavigationBarItem(
                        // Campsite details belong to the Campsites tab.
                        selected = currentRoute == tab.route ||
                            (tab.route == ADMIN_DASHBOARD_ROUTE && currentRoute == ADMIN_CAMPSITE_PATTERN),
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(ADMIN_DASHBOARD_ROUTE) { saveState = true }
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
        if (TABS.any { it.route == entrantTab }) {
            // The activity's Scaffold already pads for the system navigation bar.
            NavigationBar(windowInsets = WindowInsets(0)) {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = entrantTab == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(HOME_ROUTE) { saveState = true }
                                launchSingleTop = true
                                // Home is the popUpTo target, so restoring it would bring back the tab just saved.
                                restoreState = tab.route != HOME_ROUTE
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

/** Opens Android's photo picker; restored results wait for image loading and never encode in the view. */
@Composable
private fun rememberProfileImagePicker(controller: ProfilePhotoController, state: ProfilePhotoState): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { pending = it?.toString() }
    LaunchedEffect(pending, state.working) {
        val uri = pending ?: return@LaunchedEffect
        if (!state.working) {
            pending = null
            controller.changePhoto { encodePhoto(resolver, Uri.parse(uri)) }
        }
    }
    return {
        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: ActivityNotFoundException) { controller.pickerUnavailable() }
    }
}

/** Binds either chat entry route to one entry-scoped controller and lifecycle-aware subscription. */
@Composable
private fun ChatDestination(nav: NavHostController, repository: MessageRepository, conversationId: String? = null, campsiteId: String? = null) {
    val chat: ChatController = viewModel(factory = viewModelFactory {
        initializer { ChatController(repository, createSavedStateHandle(), conversationId, campsiteId) }
    })
    val state by chat.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(chat) { chat.resume(); onPauseOrDispose { chat.pause() } }
    ChatScreen(state, { nav.popBackStack() }, chat::retry, chat::setDraft, chat::send, chat::discardFailedDraft)
}

/**
 * Connects Android's Save dialog to the controller, without retaining CSV data in navigation state.
 * A restored picker result waits for the booking read, then is consumed before writing so
 * recreation cannot repeat a download. Cancelling the picker leaves the booking unchanged.
 */
@Composable
private fun rememberReservationDownload(controller: ReservationDetailsController, state: ReservationDetailsState): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var cancellation by rememberSaveable { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        destination = uri?.toString()
    }
    LaunchedEffect(destination, state.loading) {
        val uri = destination ?: return@LaunchedEffect
        if (!state.loading) {
            destination = null
            val write: suspend (String) -> Unit = { csv -> writeReservationCsv(resolver, Uri.parse(uri), csv) }
            if (cancellation) controller.exportCancellation(write) else controller.exportConfirmation(write)
        }
    }
    return {
        val id = state.entry?.reservation?.id.orEmpty().filter(Char::isLetterOrDigit).take(64).ifEmpty { "confirmation" }
        try {
            cancellation = state.entry?.reservation?.cancellationCompleted() == true
            save.launch("reservation-$id${if (cancellation) "-cancellation" else ""}.csv")
        } catch (_: ActivityNotFoundException) {
            controller.reportSaveUnavailable()
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
