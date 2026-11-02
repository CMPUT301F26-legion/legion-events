package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Stands in for a screen that has a route but is not built yet. The screen's owner replaces it.
 * @param title The screen's name from the mockups.
 * @param onBack Returns to the previous screen, or null for a bottom-bar tab.
 */
@Composable
fun PlaceholderScreen(title: String, onBack: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            onBack?.let { TextButton(onClick = it) { Text("Back") } }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Coming soon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
