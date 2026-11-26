/*
 * AdminLogsScreen.kt
 *
 * Purpose: S20 Admin logs: a Notifications tab (US 03.08.01) and a Messages tab of owner-to-entrant
 *   messages (US 03.12.01), newest first.
 * Role: View in MVC; renders AdminLogsState and forwards input to AdminLogsController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.AdminLogRow
import com.example.legion_events.controller.AdminLogsState
import com.example.legion_events.model.AdminLogCategory

/**
 * Shows the admin logs.
 * @param state The logs and the selected tab.
 * @param onBack Returns to the profile screen.
 * @param onRetry Reloads after a failure.
 * @param onTab Switches tabs.
 */
@Composable
fun AdminLogsScreen(
    state: AdminLogsState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTab: (AdminLogCategory) -> Unit,
) {
    val rows = if (state.tab == AdminLogCategory.NOTIFICATION) state.notifications else state.messages
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AdminHeader("Logs", onBack)
                TabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent) {
                    Tab(state.tab == AdminLogCategory.NOTIFICATION, { onTab(AdminLogCategory.NOTIFICATION) },
                        text = { Text("Notifications (${state.notifications.size})") })
                    Tab(state.tab == AdminLogCategory.MESSAGE, { onTab(AdminLogCategory.MESSAGE) },
                        text = { Text("Messages (${state.messages.size})") })
                }
            }
            when {
                state.loading && rows.isEmpty() -> item { AdminLoading("Loading logs…") }
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                rows.isEmpty() -> item {
                    Text(
                        if (state.tab == AdminLogCategory.NOTIFICATION) "No notifications have been sent yet."
                        else "No owner has messaged entrants yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(rows, key = { it.entry.category.name + it.entry.id }) { LogRow(it) }
            }
        }
    }
}

/**
 * One log entry: what it was, who sent it to whom, when, and its text.
 * @param row The labelled entry.
 */
@Composable
private fun LogRow(row: AdminLogRow) {
    AdminCard {
        Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("${row.from} → ${row.to}", style = MaterialTheme.typography.bodyMedium)
        Text(row.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row.entry.text.isNotBlank()) Text(row.entry.text, style = MaterialTheme.typography.bodyMedium)
    }
}
