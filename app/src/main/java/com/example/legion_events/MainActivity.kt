/*
 * MainActivity.kt
 *
 * Purpose: Starts the app, applies the theme, and hosts the Compose navigation.
 * Role: Android entry point; creates the controllers AppNavigation needs and handles push notification taps.
 * Outstanding issues: None.
 */
package com.example.legion_events

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.OnboardingState
import com.example.legion_events.controller.ThemeController
import com.example.legion_events.data.FirebaseRepository
import com.example.legion_events.data.LOTTERY_INBOX_RECIPIENT
import com.example.legion_events.data.ThemePreferences
import com.example.legion_events.data.WelcomePreferences
import com.example.legion_events.data.registerLotteryPushToken
import com.example.legion_events.ui.theme.DarkForest
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.example.legion_events.ui.theme.LightBackground
import com.example.legion_events.view.ThemeToggle
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Starts the app and hosts its Compose screens. */
class MainActivity : ComponentActivity() {
    private var inboxRecipient by mutableStateOf<String?>(null)
    private var showPushPermission by mutableStateOf(false)
    /**
     * Gets the retained controllers and displays navigation inside the selected app theme.
     * @param savedInstanceState Saved Android state when recreating this activity, or null on a new launch.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inboxRecipient = intent.getStringExtra(LOTTERY_INBOX_RECIPIENT)
        val controller = ViewModelProvider(
            this,
            OnboardingController.Factory(FirebaseRepository.instance),
        )[OnboardingController::class.java]
        val theme = ViewModelProvider(this, viewModelFactory {
            initializer { ThemeController(ThemePreferences(getSharedPreferences("appearance", MODE_PRIVATE))) }
        })[ThemeController::class.java]
        val welcome = WelcomePreferences(getSharedPreferences("onboarding", MODE_PRIVATE))
        enableEdgeToEdge()
        setContent {
            val pushPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refreshPushPermission()
            }
            val account by controller.state.collectAsStateWithLifecycle()
            val profile = (account as? OnboardingState.Ready)?.profile
            LaunchedEffect(profile?.uid) {
                if (profile == null) return@LaunchedEffect
                try {
                    val token = FirebaseMessaging.getInstance().token.await()
                    if (token != profile.fcmToken) registerLotteryPushToken(profile.uid, token).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w("LotteryPush", "Could not save the push registration.")
                }
            }
            val darkTheme by theme.darkTheme.collectAsStateWithLifecycle()
            // Keep system bar icons readable even when the app choice differs from the device mode.
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightBackground.toArgb(), DarkForest.toArgb()) { darkTheme },
                )
            }
            LegioneventsTheme(darkTheme = darkTheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = { ThemeToggle(darkTheme, theme::setDarkTheme) },
                ) { innerPadding ->
                    Box(Modifier.padding(innerPadding)) {
                        AppNavigation(
                            controller,
                            welcome = welcome,
                            inboxRecipient = inboxRecipient,
                            onInboxRequestConsumed = {
                                inboxRecipient = null
                                intent.removeExtra(LOTTERY_INBOX_RECIPIENT)
                            },
                            onEnablePush = if (showPushPermission) {
                                { pushPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    /** Receives a push tap when the activity already exists. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inboxRecipient = intent.getStringExtra(LOTTERY_INBOX_RECIPIENT)
    }

    /** Refreshes the permission affordance after returning from system settings. */
    override fun onResume() {
        super.onResume()
        refreshPushPermission()
    }

    /** Offers the runtime permission on Android versions that require it. */
    private fun refreshPushPermission() {
        showPushPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
}
