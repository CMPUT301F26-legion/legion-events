package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Displays the app-wide appearance switch. Tapping the label also changes the setting.
 * @param darkTheme Whether dark mode is selected.
 * @param onThemeChange Sends the new choice to the controller.
 */
@Composable
fun ThemeToggle(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .toggleable(value = darkTheme, role = Role.Switch, onValueChange = onThemeChange)
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dark mode", modifier = Modifier.weight(1f))
            Switch(checked = darkTheme, onCheckedChange = null)
        }
    }
}
