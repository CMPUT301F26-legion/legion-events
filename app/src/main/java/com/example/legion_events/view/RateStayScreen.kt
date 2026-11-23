/*
 * RateStayScreen.kt
 *
 * Purpose: Let an entrant select and submit a campsite rating after a completed stay (S10c).
 * Role: View in MVC; renders rating state and forwards actions without Firebase access.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.RateStayState

/**
 * S10c: a completed stay and an accessible one-to-five-star selection.
 * @param state Stay, selected score, and loading/save feedback from the controller.
 * @param onBack Leaves without saving; also used by Maybe later.
 * @param onRetry Reloads after an initial read failure or unavailable stay.
 * @param onScore Changes the draft score without saving it.
 * @param onSubmit Saves or retries the selected score.
 */
@Composable
fun RateStayScreen(state: RateStayState, onBack: () -> Unit, onRetry: () -> Unit,
    onScore: (Int) -> Unit, onSubmit: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row { BackArrow(onBack); Text("Rate your stay", style = MaterialTheme.typography.headlineSmall) }
            val details = state.details
            when {
                state.loading -> { CircularProgressIndicator(); Text("Loading your stay…") }
                state.error != null || details == null -> {
                    Text(state.error ?: "Only your completed stays without a pending cancellation at an available campsite can be rated.")
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    Text(details.stay.campsiteName ?: "Your completed stay", style = MaterialTheme.typography.headlineMedium)
                    Text("${reservationDateText(details.stay.reservation.startDate)} – ${reservationDateText(details.stay.reservation.endDate)}")
                    Text("How was your stay?", style = MaterialTheme.typography.titleLarge)
                    Text("One rating per campsite. Saving again updates your previous score.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (score in 1..5) Text(
                            if (score <= (state.score ?: 0)) "★" else "☆",
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .selectable(selected = state.score == score, enabled = !state.saving && !state.saved,
                                    role = Role.RadioButton, onClick = { onScore(score) })
                                .semantics { contentDescription = "$score ${if (score == 1) "star" else "stars"}" }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                            color = if (score <= (state.score ?: 0)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(state.score?.let { "$it out of 5 stars" } ?: "Choose a rating")
                    state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = onSubmit, enabled = state.score != null && !state.saving && !state.saved,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Saving rating…" else if (state.saveError != null) "Retry save" else "Save rating")
                    }
                    TextButton(onClick = onBack) { Text("Maybe later") }
                }
            }
        }
    }
}
