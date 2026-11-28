/*
 * MessagesScreen.kt
 *
 * Purpose: List private entrant–owner threads separately from announcements.
 * Role: View in MVC; renders list state and forwards navigation and Retry actions.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.MessagesState

/** Displays only the participant's private conversations; announcements stay in Inbox/Notices. */
@Composable
fun MessagesScreen(state: MessagesState, onBack: () -> Unit, onRetry: () -> Unit, onConversation: (String) -> Unit,
    onStartChat: (String) -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row { BackArrow(onBack); Text("Messages", style = MaterialTheme.typography.headlineSmall) } }
            relatedUsers(state, onRetry, onConversation, onStartChat)
            if (state.relatedUsers.isNotEmpty()) item { Text("Conversations", style = MaterialTheme.typography.titleMedium) }
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.error != null -> item { Text(state.error); Button(onClick = onRetry) { Text("Retry") } }
                state.conversations.isEmpty() && state.relatedUsers.isEmpty() -> item { Text("No private conversations yet. Open a campsite and choose Message owner.") }
                else -> items(state.conversations, key = { it.conversation.id }) { summary ->
                    OutlinedCard(onClick = { onConversation(summary.conversation.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(summary.campsiteName ?: if (summary.conversation.isPeerToPeer) "Direct message" else "Campsite unavailable", style = MaterialTheme.typography.titleMedium)
                            Text(when { summary.conversation.isPeerToPeer -> "Direct chat"
                                summary.myId == summary.conversation.ownerId -> "Chat with entrant"; else -> "Chat with owner" })
                            Text(summary.conversation.lastMessage.ifBlank { "No messages yet" }, maxLines = 2)
                            TextButton(onClick = { onConversation(summary.conversation.id) }) { Text("Open conversation") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lists related users: everyone messaged before, then unmessaged entrants of the viewer's campsites.
 * A messaged person opens their most recent thread; an unmessaged one starts a peer-to-peer chat.
 */
private fun LazyListScope.relatedUsers(state: MessagesState, onRetry: () -> Unit, onConversation: (String) -> Unit,
    onStartChat: (String) -> Unit) {
    if (state.relatedError != null) item {
        Text(state.relatedError, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
    val people = state.relatedUsers
    if (people.isEmpty()) return
    item { Text("People", style = MaterialTheme.typography.titleMedium) }
    state.startError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    items(people, key = { "user-${it.userId}" }) { person ->
        val open = person.conversationId?.let { id -> { onConversation(id) } }
        val starting = state.startingUserId == person.userId
        val tap = open ?: { onStartChat(person.userId) }
        ListItem(
            headlineContent = { Text("${person.role} …${person.userId.takeLast(6)}") },
            supportingContent = {
                Text(if (open != null) person.campsiteNames.joinToString().ifEmpty { "Messaged" }
                    else "Booked your campsite · not messaged yet", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                when {
                    open != null -> TextButton(onClick = open) { Text("Open") }
                    starting -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> TextButton(onClick = tap, enabled = state.startingUserId == null) { Text("Message") }
                }
            },
            modifier = Modifier.fillMaxWidth().clickable(enabled = state.startingUserId == null, onClick = tap),
        )
    }
}
