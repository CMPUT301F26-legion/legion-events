package com.example.legion_events

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.legion_events.controller.AppNavigation
import com.example.legion_events.controller.OnboardingController
import com.example.legion_events.data.FirebaseRepository
import com.example.legion_events.ui.theme.LegioneventsTheme

/** Starts the app and hosts its Compose screens. */
class MainActivity : ComponentActivity() {
    /**
     * Gets the retained onboarding controller and displays the app's navigation.
     * @param savedInstanceState Saved Android state when recreating this activity, or null on a new launch.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ViewModelProvider(
            this,
            OnboardingController.Factory(FirebaseRepository.instance),
        )[OnboardingController::class.java]
        enableEdgeToEdge()
        setContent {
            LegioneventsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding)) {
                        AppNavigation(controller)
                    }
                }
            }
        }
    }
}
