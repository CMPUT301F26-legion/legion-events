package com.example.legion_events.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.legion_events.R
import com.example.legion_events.controller.OnboardingState

@Composable
fun OnboardingScreen(state: OnboardingState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            OnboardingState.Loading, is OnboardingState.Ready -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.onboarding_loading))
            }
            is OnboardingState.Error -> {
                Text(stringResource(R.string.onboarding_error), style = MaterialTheme.typography.headlineSmall)
                Text(state.message, textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            OnboardingState.Blocked -> {
                Text(stringResource(R.string.account_disabled), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.account_disabled_help), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.check_again)) }
            }
        }
    }
}
