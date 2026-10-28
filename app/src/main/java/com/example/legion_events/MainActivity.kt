package com.example.legion_events

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.controller.ThemeController
import com.example.legion_events.data.FirebaseRepository
import com.example.legion_events.data.ThemePreferences
import com.example.legion_events.ui.theme.DarkForest
import com.example.legion_events.ui.theme.LegioneventsTheme
import com.example.legion_events.ui.theme.LightBackground
import com.example.legion_events.view.ThemeToggle

/** Starts the app and hosts its Compose screens. */
class MainActivity : ComponentActivity() {
    /**
     * Gets the retained controllers and displays navigation inside the selected app theme.
     * @param savedInstanceState Saved Android state when recreating this activity, or null on a new launch.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ViewModelProvider(
            this,
            OnboardingController.Factory(FirebaseRepository.instance),
        )[OnboardingController::class.java]
        val theme = ViewModelProvider(this, viewModelFactory {
            initializer { ThemeController(ThemePreferences(getSharedPreferences("appearance", MODE_PRIVATE))) }
        })[ThemeController::class.java]
        enableEdgeToEdge()
        setContent {
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
                        AppNavigation(controller)
                    }
                }
            }
        }
    }
}
