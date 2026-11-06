/* S07 presents saved lottery results and links winners to their booking offers. */
package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.InboxState
import com.example.legion_events.model.LotteryNotification
import com.example.legion_events.model.OwnerNotice
import java.text.DateFormat

/** Shows loading, retry, empty, or populated inbox state with optional push permission and offer actions. */
@Composable
fun InboxScreen(
    state: InboxState,
    onRetry: () -> Unit,
    onOffer: (String) -> Unit,
    onEnablePush: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Inbox", style = MaterialTheme.typography.headlineMedium)
            Text("Your campsite lottery results and owner notices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onEnablePush != null) {
            item { OutlinedButton(onClick = onEnablePush) { Text("Allow push notifications") } }
        }
        when (state) {
            InboxState.Loading -> item { CircularProgressIndicator() }
            InboxState.Failed -> item {
                Text("Could not load your inbox.")
                Button(onClick = onRetry) { Text("Retry") }
            }
            is InboxState.Loaded -> {
                if (state.notifications.isEmpty()) item { Text("No lottery results yet.") }
                items(state.notifications, key = { it.id }) { notification ->
                    LotteryResultCard(notification, onOffer)
                }
                if (state.notices.isNotEmpty()) {
                    item { Text("Notices from owners", style = MaterialTheme.typography.titleMedium) }
                    items(state.notices, key = { "notice-" + it.id }) { notice -> OwnerNoticeCard(notice) }
                }
            }
        }
    }
}

/** Displays one result and opens the attached offer when the winner selects it. */
@Composable
private fun LotteryResultCard(notification: LotteryNotification, onOffer: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(notification.result.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(notification.result.message))
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(notification.createdAt.toDate()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notification.offerId?.let { offerId ->
                Button(onClick = { onOffer(offerId) }) { Text("View booking offer") }
            }
        }
    }
}

/** Displays one notice an owner sent to a waitlist the entrant joined (US 02.10.01). */
@Composable
private fun OwnerNoticeCard(notice: OwnerNotice) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Notice from a campsite owner", style = MaterialTheme.typography.titleMedium)
            Text(notice.message)
            notice.createdAt?.let {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.toDate()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
