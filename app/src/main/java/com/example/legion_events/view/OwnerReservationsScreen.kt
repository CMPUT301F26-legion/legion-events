/*
 * OwnerReservationsScreen.kt
 *
 * Purpose: S17 owner reservations: campsite dropdown, booked-night calendar, Active, Past, and
 *   Cancelled lists, booking details, and rejection (US 02.06.01, 02.07.01 to 02.07.03, 02.08.01).
 * Role: View in MVC; renders OwnerReservationsState and forwards input to OwnerReservationsController.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.OwnerReservationTab
import com.example.legion_events.controller.OwnerReservationsState
import com.example.legion_events.controller.RejectionDraft
import com.example.legion_events.model.Reservation
import com.example.legion_events.model.calendarCells
import com.example.legion_events.model.monthTitle
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The user actions S17 forwards to its controller.
 * @property onRefresh Reloads campsites and bookings, also used as Retry.
 * @property onSelectCampsite Shows another campsite's bookings.
 * @property onTab Shows another booking list.
 * @property onMonth Moves the calendar by the given number of months.
 * @property onView Opens a booking's details.
 * @property onCloseView Closes the booking details.
 * @property onReject Opens the rejection dialog for a booking.
 * @property onReason Updates the rejection reason.
 * @property onConfirmReject Rejects the booking.
 * @property onCancelReject Closes the rejection dialog.
 */
data class OwnerReservationsActions(
    val onRefresh: () -> Unit = {},
    val onSelectCampsite: (String) -> Unit = {},
    val onTab: (OwnerReservationTab) -> Unit = {},
    val onMonth: (Int) -> Unit = {},
    val onView: (Reservation) -> Unit = {},
    val onCloseView: () -> Unit = {},
    val onReject: (Reservation) -> Unit = {},
    val onReason: (String) -> Unit = {},
    val onConfirmReject: () -> Unit = {},
    val onCancelReject: () -> Unit = {},
)

/**
 * Shows the owner's bookings for one campsite at a time.
 * @param state Campsites, the calendar, the booking lists, and open dialogs.
 * @param actions Callbacks for every control on the screen.
 */
@Composable
fun OwnerReservationsScreen(state: OwnerReservationsState, actions: OwnerReservationsActions) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reservations", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onRefresh, enabled = !state.loading) { Text("Refresh") }
                }
            }
            when {
                state.error != null -> item {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = actions.onRefresh, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                state.loading && state.campsites.isEmpty() -> item { Loading() }
                state.campsites.isEmpty() -> item {
                    Text("You have no campsites yet. Add one from the Dashboard to see its reservations.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    item { CampsiteMenu(state, actions.onSelectCampsite) }
                    item { BookingCalendar(state, actions.onMonth) }
                    item { BookingTabs(state.selectedTab, actions.onTab) }
                    state.result?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                    val entries = state.visibleEntries()
                    when {
                        state.loading -> item { Loading() }
                        entries.isEmpty() -> item { EmptyList(state.selectedTab) }
                        else -> items(entries, key = { it.id }) { entry ->
                            BookingCard(entry, state.selectedTab == OwnerReservationTab.ACTIVE, actions)
                        }
                    }
                }
            }
        }
    }
    state.viewing?.let { BookingDialog(it, state.selectedTab == OwnerReservationTab.ACTIVE, actions) }
    state.rejecting?.let { RejectDialog(it, actions) }
}

/** A centred spinner with a caption, shown while reading. */
@Composable
private fun Loading() {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text("Loading reservations…", Modifier.padding(top = 12.dp))
    }
}

/**
 * The dropdown that picks which owned campsite to show.
 * @param state Supplies the campsites and the selection.
 * @param onSelect Called with the chosen campsite ID.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampsiteMenu(state: OwnerReservationsState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.campsites.firstOrNull { it.id == state.selectedCampsiteId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name.orEmpty(), onValueChange = {}, readOnly = true,
            label = { Text("Campsite") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.campsites.forEach { site ->
                DropdownMenuItem(text = { Text(site.name) }, onClick = { onSelect(site.id); expanded = false })
            }
        }
    }
}

/**
 * A month grid that marks nights held by confirmed bookings and outlines nights asked for by
 * requested bookings (US 02.06.01).
 * @param state Supplies the month and the booked and requested nights.
 * @param onMonth Moves the calendar by the given number of months.
 */
@Composable
private fun BookingCalendar(state: OwnerReservationsState, onMonth: (Int) -> Unit) {
    if (state.month.isEmpty()) return
    val cells = calendarCells(state.month)
    val bookedThisMonth = cells.count { it != null && it in state.bookedNights }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onMonth(-1) }, modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Previous month"
                }) { Text("‹") }
                Text(monthTitle(state.month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { onMonth(1) }, modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Next month"
                }) { Text("›") }
            }
            Row {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                    Text(day, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            cells.chunked(7).forEach { week ->
                Row {
                    week.forEach { night -> DayCell(night, nightStatus(state, night), Modifier.weight(1f)) }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape))
                Text("Booked", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                if (state.requestedNights.isNotEmpty()) {
                    Box(Modifier.padding(start = 12.dp).size(10.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.error, CircleShape))
                    Text("Requested", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("$bookedThisMonth ${if (bookedThisMonth == 1) "night" else "nights"} booked",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How a calendar night is held. */
private enum class NightStatus { FREE, REQUESTED, BOOKED }

/**
 * Looks up how a night is held; a confirmed booking wins over a request.
 * @param state Supplies the booked and requested nights.
 * @param night The `YYYY-MM-DD` night, or null for a blank cell.
 * @return The night's status.
 */
private fun nightStatus(state: OwnerReservationsState, night: String?): NightStatus = when (night) {
    null -> NightStatus.FREE
    in state.bookedNights -> NightStatus.BOOKED
    in state.requestedNights -> NightStatus.REQUESTED
    else -> NightStatus.FREE
}

/**
 * One calendar day, filled when a confirmed booking holds that night and outlined when a
 * requested booking asks for it.
 * @param night The `YYYY-MM-DD` night, or null for a blank cell.
 * @param status How the night is held.
 * @param modifier Gives the cell its share of the row.
 */
@Composable
private fun DayCell(night: String?, status: NightStatus, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
        if (night == null) return@Box
        val day = night.takeLast(2).trimStart('0')
        val colors = MaterialTheme.colorScheme
        val (fill, text, label) = when (status) {
            NightStatus.BOOKED -> Triple(colors.errorContainer, colors.onErrorContainer, "${nightText(night)}, booked")
            NightStatus.REQUESTED -> Triple(colors.surface, colors.onSurface, "${nightText(night)}, requested")
            NightStatus.FREE -> Triple(colors.surface, colors.onSurface, nightText(night))
        }
        val outline = if (status == NightStatus.REQUESTED) {
            Modifier.border(1.5.dp, colors.error, CircleShape)
        } else {
            Modifier
        }
        Box(
            Modifier.fillMaxSize().background(fill, CircleShape).then(outline)
                .clearAndSetSemantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Text(day, style = MaterialTheme.typography.bodySmall, color = text)
        }
    }
}

/**
 * The Active, Past, and Cancelled tabs.
 * @param selected The tab shown.
 * @param onTab Called with the tapped tab.
 */
@Composable
private fun BookingTabs(selected: OwnerReservationTab, onTab: (OwnerReservationTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.background) {
        OwnerReservationTab.entries.forEach { tab ->
            Tab(selected = tab == selected, onClick = { onTab(tab) }, text = { Text(tabLabel(tab)) })
        }
    }
}

/**
 * Explains an empty list.
 * @param tab The list that is empty.
 */
@Composable
private fun EmptyList(tab: OwnerReservationTab) {
    Column {
        Text("No ${tabLabel(tab).lowercase()} reservations", style = MaterialTheme.typography.titleMedium)
        Text(when (tab) {
            OwnerReservationTab.ACTIVE -> "Upcoming and ongoing requested and confirmed stays at this campsite appear here."
            OwnerReservationTab.PAST -> "Confirmed stays appear here after checkout."
            OwnerReservationTab.CANCELLED -> "Cancelled and rejected bookings appear here."
        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One booking with its status, dates, and actions.
 * @param booking The booking.
 * @param canReject Whether to offer cancellation or request rejection before checkout.
 * @param actions Opens the details or opens the rejection dialog.
 */
@Composable
private fun BookingCard(booking: Reservation, canReject: Boolean, actions: OwnerReservationsActions) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stayText(booking), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusBadge(booking.status)
            }
            Text("${guestText(booking.guestCount)} · Reservation ${booking.id}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (booking.cancellationRequested && !booking.isCancelled()) {
                Text("Cancellation requested", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.onView(booking) }) { Text("View booking") }
                Spacer(Modifier.weight(1f))
                if (canReject) {
                    OutlinedButton(onClick = { actions.onReject(booking) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(if (booking.status == Reservation.STATUS_CONFIRMED) "Cancel reservation" else "Reject request")
                    }
                }
            }
        }
    }
}

/**
 * A small pill with the booking's status.
 * @param status The stored status.
 */
@Composable
private fun StatusBadge(status: String) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        Reservation.STATUS_CONFIRMED -> colors.primaryContainer to colors.onPrimaryContainer
        Reservation.STATUS_REQUESTED -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.errorContainer to colors.onErrorContainer
    }
    Text(reservationStatusText(status), color = content, style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.background(container, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/**
 * Booking details: reservation number, dates, guests, total, and rules acceptance. Entrant profiles
 * are private under the Firestore rules, so no name is shown.
 * @param booking The booking.
 * @param canReject Whether to offer cancellation or request rejection.
 * @param actions Closes the dialog or opens the rejection dialog.
 */
@Composable
private fun BookingDialog(booking: Reservation, canReject: Boolean, actions: OwnerReservationsActions) {
    AlertDialog(
        onDismissRequest = actions.onCloseView,
        title = { Text("Booking details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reservation number: ${booking.id}")
                Text("Check-in: ${dayFormat().format(booking.startDate.toDate())}")
                Text("Checkout: ${dayFormat().format(booking.endDate.toDate())}")
                Text("Guests: ${booking.guestCount}")
                Text("Total: ${priceText(booking.totalPrice)}")
                Text("Rules accepted: ${if (booking.rulesAccepted) "Yes" else "No"}")
                Text("Status: ${reservationStatusText(booking.status)}")
                if (booking.cancellationRequested) Text("The entrant requested a cancellation.")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = actions.onCloseView) { Text("Close") }
            }
        },
        dismissButton = if (canReject) {
            { TextButton(onClick = { actions.onReject(booking) }) {
                Text(if (booking.status == Reservation.STATUS_CONFIRMED) "Cancel reservation" else "Reject request",
                    color = MaterialTheme.colorScheme.error)
            } }
        } else null,
    )
}

/**
 * Confirms cancellation of a confirmed stay or rejection of a request, with an optional entrant notice reason.
 * @param draft The booking, reason, and progress.
 * @param actions Updates the reason, confirms, or cancels.
 */
@Composable
private fun RejectDialog(draft: RejectionDraft, actions: OwnerReservationsActions) {
    val booking = draft.reservation
    val cancelling = booking.status == Reservation.STATUS_CONFIRMED
    AlertDialog(
        onDismissRequest = actions.onCancelReject,
        title = { Text(if (cancelling) "Cancel this reservation?" else "Reject this booking request?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (cancelling) "The reservation for ${stayText(booking)} will be cancelled. The entrant will be notified and the reserved dates will be released."
                    else "The request for ${stayText(booking)} will be rejected, and the entrant will be notified.")
                OutlinedTextField(
                    value = draft.reason, onValueChange = actions.onReason, enabled = !draft.inProgress,
                    label = { Text("Reason (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    isError = draft.error != null,
                    supportingText = {
                        Text(draft.error ?: "${draft.reason.length} / ${Reservation.MAX_REJECTION_REASON_LENGTH}")
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = actions.onConfirmReject, enabled = !draft.inProgress,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) {
                Text(if (cancelling) {
                    if (draft.inProgress) "Cancelling…" else "Cancel reservation"
                } else {
                    if (draft.inProgress) "Rejecting…" else "Reject request"
                })
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelReject, enabled = !draft.inProgress) { Text("Keep booking") }
        },
    )
}

/**
 * Names a tab.
 * @param tab The tab.
 * @return Its label.
 */
private fun tabLabel(tab: OwnerReservationTab): String = when (tab) {
    OwnerReservationTab.ACTIVE -> "Active"
    OwnerReservationTab.PAST -> "Past"
    OwnerReservationTab.CANCELLED -> "Cancelled"
}

/**
 * Creates the UTC date formatter used on S17, so card dates match the calendar's nights.
 * @return A formatter such as "Jul 4, 2027".
 */
private fun dayFormat() = SimpleDateFormat("MMM d, yyyy", Locale.CANADA).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Formats a night key for display and screen readers.
 * @param night A `YYYY-MM-DD` night.
 * @return Text such as "Jul 4, 2027".
 */
private fun nightText(night: String): String {
    val read = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return read.parse(night)?.let { dayFormat().format(it) } ?: night
}

/**
 * Formats a stay as check-in to checkout in UTC days.
 * @param booking The booking.
 * @return Text such as "Jul 4, 2027 to Jul 7, 2027".
 */
private fun stayText(booking: Reservation): String =
    "${dayFormat().format(booking.startDate.toDate())} to ${dayFormat().format(booking.endDate.toDate())}"

/**
 * Formats a guest count.
 * @param count Number of guests.
 * @return Text such as "1 guest" or "4 guests".
 */
private fun guestText(count: Int): String = if (count == 1) "1 guest" else "$count guests"

/**
 * Formats integer CAD cents as an exact total.
 * @param cents The total in cents.
 * @return Text such as "$65.50 CAD".
 */
private fun priceText(cents: Long): String =
    "${NumberFormat.getCurrencyInstance(Locale.CANADA).format(BigDecimal.valueOf(cents, 2))} CAD"
