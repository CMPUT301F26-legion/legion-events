package com.example.legion_events.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * S01: introduces the lottery and explains that the device identifies the user.
 * @param onStart Opens campsite search.
 * @param onPolicy Opens the app policy and guidelines.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onPolicy: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("CampLot", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("Alberta adventures start here", color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Text("A fair chance to get outdoors.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Enter a campsite lottery during its entry period. If selected, choose from the dates still available.")
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Text("Your device identifies you. No username or password is needed.", Modifier.padding(12.dp))
            }
            Text("Add your name and contact details in your profile.")
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start exploring") }
            TextButton(onClick = onPolicy) { Text("App policy & guidelines") }
        }
    }
}
