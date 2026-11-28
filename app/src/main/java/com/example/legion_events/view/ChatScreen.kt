/*
 * ChatScreen.kt
 *
 * Purpose: Show private messages and a two-way entrant–owner composer.
 * Role: View in MVC; renders chat state and forwards drafts, sends, and Retry.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.ChatState
import com.example.legion_events.model.MAX_CHAT_LENGTH

/** Displays a private conversation without any Firebase calls or automatic sends. */
@Composable
fun ChatScreen(state: ChatState, onBack: () -> Unit, onRetry: () -> Unit, onDraft: (String) -> Unit,
    onSend: () -> Unit, onDiscardDraft: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text(state.thread?.campsiteName ?: if (state.thread?.conversation?.isPeerToPeer == true) "Direct message" else "Private conversation", style = MaterialTheme.typography.headlineSmall)
            }
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); Button(onClick = onRetry) { Text("Retry") } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages, key = { it.id }) { message ->
                    val mine = message.senderId == state.thread?.myId
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(12.dp).widthIn(max = 280.dp)) {
                                Text(when { mine -> "You"; state.thread?.conversation?.isPeerToPeer == true -> "Them"
                                    message.senderId == state.thread?.conversation?.ownerId -> "Owner"; else -> "Entrant" },
                                    style = MaterialTheme.typography.labelSmall)
                                Text(message.text)
                            }
                        }
                    }
                }
            }
            if (state.thread != null) {
                if (state.messages.isEmpty()) Text("No messages yet. Start a private conversation.")
                OutlinedTextField(state.draft, onValueChange = onDraft, label = { Text("Message") },
                    enabled = !state.sending && state.sendError == null, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    supportingText = { Text("${state.draft.length}/$MAX_CHAT_LENGTH") })
                state.sendError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Text("The message may already have been delivered. Discard only clears this draft.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDiscardDraft, enabled = !state.sending) { Text("Discard draft") }
                }
                Button(onClick = onSend, enabled = !state.sending && state.draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.sending) "Sending…" else if (state.sendError != null) "Retry send" else "Send")
                }
            }
        }
    }
}
