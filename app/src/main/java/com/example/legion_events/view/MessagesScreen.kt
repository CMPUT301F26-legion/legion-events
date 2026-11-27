/*
 * MessagesScreen.kt
 *
 * Purpose: List private entrant–owner threads separately from announcements.
 * Role: View in MVC; renders list state and forwards navigation and Retry actions.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.MessagesState

/** Displays only the participant's private conversations; announcements stay in Inbox/Notices. */
@Composable
fun MessagesScreen(state: MessagesState, onBack: () -> Unit, onRetry: () -> Unit, onConversation: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row { BackArrow(onBack); Text("Messages", style = MaterialTheme.typography.headlineSmall) } }
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.error != null -> item { Text(state.error); Button(onClick = onRetry) { Text("Retry") } }
                state.conversations.isEmpty() -> item { Text("No private conversations yet. Open a campsite and choose Message owner.") }
                else -> items(state.conversations, key = { it.conversation.id }) { summary ->
                    OutlinedCard(onClick = { onConversation(summary.conversation.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(summary.campsiteName ?: "Campsite unavailable", style = MaterialTheme.typography.titleMedium)
                            Text(if (summary.myId == summary.conversation.ownerId) "Chat with entrant" else "Chat with owner")
                            Text(summary.conversation.lastMessage.ifBlank { "No messages yet" }, maxLines = 2)
                            TextButton(onClick = { onConversation(summary.conversation.id) }) { Text("Open conversation") }
                        }
                    }
                }
            }
        }
    }
}
