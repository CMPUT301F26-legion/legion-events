/*
 * LotteriesScreen.kt
 *
 * Purpose: Give entrants a home for lottery entries and booking offers under the Lotteries tab.
 * Role: View in MVC; forwards offer navigation to AppNavigation.
 * Outstanding issues: Part 5 supplies the entry list and lottery-result inbox.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * S06: groups entry history and the Part 6 offer flow under the entrant's Lotteries tab.
 * Entry history stays explicitly unavailable until its repository and screen are connected.
 * @param onOffers Opens the authenticated user's booking offers.
 */
@Composable
fun LotteriesScreen(onOffers: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text("My lotteries", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your entries and opportunities to book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Booking offers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Selected in a lottery?", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Review your offers and respond before their deadlines.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onOffers, modifier = Modifier.fillMaxWidth()) { Text("View offers") }
                    }
                }
            }
            item {
                Text("Lottery entries", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Entry history is coming soon.", modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
