/*
 * BookingFlowScreen.kt
 * Purpose: Winner campsite/date selection, policy agreement, review, and explicit reservation request.
 * Role: View in MVC; displays controller state and forwards actions without database access.
 * Outstanding issues: Automatic confirmation tracking and Inbox integration follow in PR 4.
 */
package com.example.legion_events.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.legion_events.controller.BookingFlowState
import com.example.legion_events.model.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Callbacks owned by BookingFlowController or the shared router. */
data class BookingFlowActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onCampsite: (String) -> Unit,
    val onCheckIn: (Long) -> Unit,
    val onCheckOut: (Long) -> Unit,
    val onGuests: (Int) -> Unit,
    val onRules: (Boolean) -> Unit,
    val onReview: () -> Unit,
    val onDismissReview: () -> Unit,
    val onConfirm: () -> Unit,
    val onRetrySubmission: () -> Unit,
    val onChooseAgain: () -> Unit,
    val onViewReservation: (String) -> Unit,
)

/** Shows current options and a final review dialog; selection itself never holds campsite nights. */
@Composable
fun BookingFlowScreen(state: BookingFlowState, actions: BookingFlowActions) {
    var dateField by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = state.submitting) { /* The immutable request is being submitted. */ }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BackArrow(actions.onBack, enabled = !state.submitting)
                Text("Reserve a campsite", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.weight(1f).testTag("booking-choices"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    state.loading -> item { CircularProgressIndicator(); Text("Loading booking choices…") }
                    state.error != null -> item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = actions.onReload) { Text("Retry") }
                    }
                    state.result != null -> item { BookingResult(state, actions) }
                    state.pendingAttempt != null -> item {
                        val request = state.pendingAttempt
                        Text(if (state.submitting) "Sending your request…" else "Check your booking request",
                            style = MaterialTheme.typography.titleLarge)
                        BookingSummary(request.campsiteId, request)
                        if (state.submitting) CircularProgressIndicator()
                        else {
                            Text(state.message ?: "Your request may have been received. Check its status before changing your selection.")
                            Button(onClick = actions.onRetrySubmission, enabled = state.submissionEnabled) { Text("Retry same request") }
                            OutlinedButton(onClick = actions.onReload) { Text("Check request status") }
                        }
                    }
                    state.options != null -> {
                        item {
                            Text("Choose your stay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            val options = state.options
                            Text("Respond by ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(options.lottery.bookingEndAt.toDate())}")
                            if (!options.canAccept(state.checkedAt)) Text(
                                if (state.checkedAt < options.lottery.bookingStartAt) "Booking has not opened yet."
                                else "This offer is no longer available for booking.",
                                color = MaterialTheme.colorScheme.error)
                            Text("Choosing a site does not hold it. Availability is checked again when your request is confirmed.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.options.campsites.isEmpty() ||
                            state.options.campsites.none { bookingCheckIns(it, state.checkedAt).isNotEmpty() }) item {
                            Text("No campsite dates are currently available.", style = MaterialTheme.typography.titleMedium)
                        }
                        item { Text("Choose one campsite", style = MaterialTheme.typography.titleLarge) }
                        item {
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.options.campsites.forEach { choice -> BookingChoice(choice, state, actions.onCampsite) }
                            }
                        }
                        state.selected?.let { selected ->
                            item {
                                Text("Camping dates", style = MaterialTheme.typography.titleLarge)
                                Text("Choose check-in, then checkout. Unavailable dates are disabled.")
                                OutlinedButton(onClick = { dateField = "start" }, enabled = state.editable &&
                                    bookingCheckIns(selected, state.checkedAt).isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("booking-check-in")) {
                                    Text("Check-in: ${state.selection.checkInMillis?.let(::bookingDayText) ?: "Choose date"}")
                                }
                                OutlinedButton(onClick = { dateField = "end" }, enabled = state.editable &&
                                    state.selection.checkInMillis != null, modifier = Modifier.fillMaxWidth().testTag("booking-check-out")) {
                                    Text("Checkout: ${state.selection.checkOutMillis?.let(::bookingDayText) ?: "Choose date"}")
                                }
                                Text("The checkout date is not an overnight stay.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item {
                                Text("Guests", style = MaterialTheme.typography.titleLarge)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    OutlinedButton(onClick = { actions.onGuests(state.selection.guests - 1) },
                                        enabled = state.editable && state.selection.guests > 1,
                                        modifier = Modifier.semantics { contentDescription = "Decrease guests" }) { Text("−") }
                                    Text("${state.selection.guests} ${if (state.selection.guests == 1) "guest" else "guests"}")
                                    OutlinedButton(onClick = { actions.onGuests(state.selection.guests + 1) },
                                        enabled = state.editable && state.selection.guests < selected.campsite.maxGuests,
                                        modifier = Modifier.semantics { contentDescription = "Increase guests" }) { Text("+") }
                                }
                                Text("Up to ${selected.campsite.maxGuests} guests")
                                state.selection.total(selected.campsite)?.let {
                                    Text("Total: ${shortPrice(it)} CAD", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            item { BookingPolicies(BookingTerms.from(selected.campsite)) }
                            item {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(value = state.rulesAccepted, enabled = state.editable, role = Role.Checkbox,
                                        onValueChange = actions.onRules).padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = state.rulesAccepted, onCheckedChange = null, enabled = state.editable)
                                    Text("I agree to the campsite rules and cancellation policy.", Modifier.padding(start = 8.dp))
                                }
                            }
                            if (!state.canReview) item {
                                state.selection.problem(selected, state.checkedAt)?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        state.message?.let { message -> item {
                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        } }
                        item { TextButton(onClick = actions.onReload, enabled = !state.busy) { Text("Refresh booking choices") } }
                    }
                }
            }
            if (state.options != null && state.result == null && state.pendingAttempt == null && state.error == null) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.submissionEnabled) Text("Reservations are not available yet. You can review campsites and dates.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = actions.onReview, enabled = state.canReview, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.reviewing) "Checking availability…" else "Review reservation")
                        }
                    }
                }
            }
        }
    }
    state.review?.let { request ->
        AlertDialog(onDismissRequest = actions.onDismissReview,
            title = { Text("Confirm your reservation request") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BookingSummary(state.selected?.campsite?.name ?: request.campsiteId, request)
                    BookingPolicies(request.terms)
                    Text("You accepted these rules and the cancellation policy.")
                    Text("Your request will be confirmed only if every selected night is still available.")
                    if (!state.submissionEnabled) Text("Reservations are not available yet.")
                }
            },
            confirmButton = { TextButton(onClick = actions.onConfirm, enabled = state.submissionEnabled && !state.busy) { Text("Confirm reservation") } },
            dismissButton = { TextButton(onClick = actions.onDismissReview) { Text("Back to selection") } })
    }
    val choice = state.selected
    if (dateField != null && state.editable && choice != null) {
        val checkIn = state.selection.checkInMillis
        val valid = if (dateField == "start") bookingCheckIns(choice, state.checkedAt)
            else checkIn?.let { bookingCheckouts(choice, it) }.orEmpty()
        BookingDateDialog(if (dateField == "start") "Choose check-in date" else "Choose checkout date", valid,
            if (dateField == "start") checkIn else state.selection.checkOutMillis,
            onDismiss = { dateField = null },
            onDate = { value -> if (dateField == "start") actions.onCheckIn(value) else actions.onCheckOut(value); dateField = null })
    }
}

/** One radio-selectable offered campsite, named with its capacity, price, and current availability. */
@Composable
private fun BookingChoice(choice: BookingCampsite, state: BookingFlowState, onSelect: (String) -> Unit) {
    val campsite = choice.campsite
    val selected = campsite.id == state.selection.campsiteId
    val count = bookingCheckIns(choice, state.checkedAt).size
    OutlinedCard(modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
        enabled = state.editable && count > 0, onClick = { onSelect(campsite.id) }),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null, enabled = state.editable && count > 0)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(campsite.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(campsite.locationName)
                Text("${campsitePrice(campsite.pricePerNight)} · Up to ${campsite.maxGuests} guests")
                Text(if (count == 0) "No available dates" else "$count available ${if (count == 1) "night" else "nights"}")
                if (campsite.description.isNotBlank()) Text(campsite.description)
                if (campsite.amenities.isNotEmpty()) Text(campsite.amenities.joinToString(" · "))
            }
        }
    }
}

/** Policies shown in full before agreement and repeated in the immutable final review. */
@Composable
private fun BookingPolicies(terms: BookingTerms) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Campsite rules", style = MaterialTheme.typography.titleMedium)
        if (terms.rules.none { it.isNotBlank() }) Text("No additional campsite rules provided.")
        else terms.rules.filter { it.isNotBlank() }.forEach { Text("• $it") }
        Text("Cancellation policy", style = MaterialTheme.typography.titleMedium)
        Text(terms.cancellationPolicy.ifBlank { "No cancellation policy provided." })
    }
}

/** Readable exact selection; checkout remains excluded from the night count. */
@Composable
private fun BookingSummary(name: String, request: BookingRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        Text("Check-in: ${bookingDayText(request.startDate.toDate().time)}")
        Text("Checkout: ${bookingDayText(request.endDate.toDate().time)}")
        val nights = bookingNightKeys(request.startDate, request.endDate).size
        Text("$nights ${if (nights == 1) "night" else "nights"} · ${request.guestCount} ${if (request.guestCount == 1) "guest" else "guests"}")
        Text("Total: ${shortPrice(request.totalPrice)} CAD", fontWeight = FontWeight.SemiBold)
    }
}

/** Reports saved processing state; a requested record never displays a booking confirmation. */
@Composable
private fun BookingResult(state: BookingFlowState, actions: BookingFlowActions) {
    val result = checkNotNull(state.result)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (result.status) {
            Reservation.STATUS_CONFIRMED -> "Reservation confirmed"
            Reservation.STATUS_REJECTED -> "Request not confirmed"
            Reservation.STATUS_REQUESTED -> "Confirming your reservation…"
            else -> "Booking request status: ${reservationStatusText(result.status)}"
        }, style = MaterialTheme.typography.headlineSmall)
        Text("Request reference: ${result.id}")
        Text("Check-in: ${bookingDayText(result.startDate.toDate().time)}")
        Text("Checkout: ${bookingDayText(result.endDate.toDate().time)}")
        Text("${result.guestCount} ${if (result.guestCount == 1) "guest" else "guests"} · ${shortPrice(result.totalPrice)} CAD total")
        when (result.status) {
            Reservation.STATUS_REQUESTED -> {
                CircularProgressIndicator()
                Text("We're checking and reserving your selected dates. Your confirmation will appear automatically when your booking is complete. No owner approval is needed.")
                Text("You can leave this screen and check your booking later. Your dates are reserved only after confirmation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = actions.onReload) { Text("Check request status") }
            }
            Reservation.STATUS_REJECTED -> {
                Text("Your selected stay could not be confirmed. Check the offer for another available choice.")
                Button(onClick = actions.onChooseAgain) { Text("Review availability again") }
            }
            Reservation.STATUS_CONFIRMED -> Button(onClick = { actions.onViewReservation(result.id) }) { Text("View booking") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = actions.onBack) { Text("Back to offer") }
    }
}

/** UTC date labels match owner-listed night keys in every device time zone. */
internal fun bookingDayText(millis: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

/** Material date picker allows only the supplied check-in or checkout boundaries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingDateDialog(title: String, valid: Set<Long>, initial: Long?, onDismiss: () -> Unit, onDate: (Long) -> Unit) {
    if (valid.isEmpty()) return
    val years = remember(valid) { valid.map { SimpleDateFormat("yyyy", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it)).toInt() }.toSet() }
    key(title, valid) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial?.takeIf { it in valid },
            initialDisplayedMonthMillis = initial?.takeIf { it in valid } ?: valid.min(),
            yearRange = years.min()..years.max(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in valid
                override fun isSelectableYear(year: Int) = year in years
            })
        DatePickerDialog(onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.takeIf { it in valid }?.let(onDate) },
                enabled = picker.selectedDateMillis in valid) { Text("Use date") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) {
            DatePicker(state = picker, title = { Text(title, Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}
