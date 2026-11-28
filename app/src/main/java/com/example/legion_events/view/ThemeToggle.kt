/*
 * ThemeToggle.kt
 *
 * Purpose: The top bar's text size selector and light/dark appearance switch.
 * Role: View in MVC; forwards changes to ThemeController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.legion_events.model.TextSize

/**
 * Displays the app-wide text size choice and appearance switch. Tapping "Dark mode" also changes the setting.
 * @param darkTheme Whether dark mode is selected.
 * @param onThemeChange Sends the new appearance to the controller.
 * @param textSize The selected text size.
 * @param onTextSizeChange Sends the new text size to the controller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeToggle(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    textSize: TextSize = TextSize.DEFAULT,
    onTextSizeChange: (TextSize) -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow {
                val styles = listOf(MaterialTheme.typography.labelSmall, MaterialTheme.typography.labelLarge,
                    MaterialTheme.typography.titleMedium)
                TextSize.entries.forEachIndexed { index, size ->
                    SegmentedButton(
                        selected = size == textSize, onClick = { onTextSizeChange(size) },
                        shape = SegmentedButtonDefaults.itemShape(index, TextSize.entries.size),
                        icon = {}, modifier = Modifier.semantics { contentDescription = size.label },
                    ) { Text("A", style = styles[index]) }
                }
            }
            Row(
                modifier = Modifier.weight(1f)
                    .toggleable(value = darkTheme, role = Role.Switch, onValueChange = onThemeChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dark mode", modifier = Modifier.weight(1f))
                Switch(checked = darkTheme, onCheckedChange = null)
            }
        }
    }
}
