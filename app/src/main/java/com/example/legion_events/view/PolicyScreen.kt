package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Policy sections shown in order, as heading and body text. */
private val POLICY = listOf(
    "Fair lotteries" to "Each lottery draws winners at random from everyone on its waiting list once registration closes. " +
        "Joining early does not improve your chance. Join each waiting list only once.",
    "Your device is your account" to "The app identifies you by this device, so no username or password is needed. " +
        "Uninstalling the app or clearing its data can start a new account.",
    "Your information" to "Your name, email, and phone number are shared only with owners of campsites you enter or reserve. " +
        "You can change them or delete your profile at any time in Profile & settings.",
    "Notifications" to "Lottery results and reservation updates are sent as notifications. " +
        "You can turn them off in Profile & settings and check your inbox instead.",
    "Reservations" to "If you win, reserve from the dates still available before the offer expires, or decline it. " +
        "Read each campsite's rules and cancellation policy before reserving.",
    "Owners" to "Owners must describe campsites accurately and may only contact entrants about their lottery or reservation.",
    "Enforcement" to "Administrators may remove campsites, images, or profiles that break these guidelines.",
)

/**
 * Shows the app policy and guidelines.
 * @param onBack Returns to the previous screen.
 */
@Composable
fun PolicyScreen(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { TextButton(onClick = onBack) { Text("Back") } }
            item { Text("App policy & guidelines", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            items(POLICY, key = { it.first }) { (heading, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
