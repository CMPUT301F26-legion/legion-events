# Winner booking selection and review (PR 3)

This connects US 01.04.01, US 01.04.02, and US 02.11.01 to the
[booking repository contract](booking-flow.md) and [foreground owner processor](local-booking-processing.md).
The entry is **Lotteries → Booking offers → View offer → Reserve a campsite**.
Complete open offers expose the entry; legacy offers without campsite choices,
accepted offers, and terminal/expired offers retain their existing behavior.

## Entrant flow

1. Read the owned offer's current campsite choices, provisional night locks, and
   previous requests from the server.
2. Select one named campsite. Its location, description, amenities, guest capacity,
   nightly CAD price, and number of available current/future nights are shown.
3. Choose check-in and checkout using date pickers. Unavailable check-in dates are
   disabled; checkout choices stop at the first gap/occupied night. Checkout can
   follow the last free night because it is excluded from the stay.
4. Choose guests within capacity and inspect the exact CAD total.
5. Read the rules and cancellation policy in full and explicitly check agreement.
6. Select **Review reservation**. A new server read checks availability, price,
   policies, and the offer window before showing an immutable summary.
7. Select **Confirm reservation**. Only this action submits acceptance and creates
   a request. Back to selection creates nothing. A requested record displays
   **Awaiting confirmation** and its reference, never a success receipt.

All camping dates use UTC calendar days, matching owner-listed nights and locks,
regardless of device time zone or daylight saving. The offer's booking deadline is
the submission window and does not restrict the stay to that date range.
Changing campsite resets dates and guest count. Changing campsite, dates, or guests
clears agreement; changed rules, policy, or price at review also require acceptance
again. The foreground owner processor confirms the request and reserves nights atomically.

## State, retries, and navigation

BookingSelection provides pure check-in/checkout choices, validation, and integer
price arithmetic. BookingFlowController owns selection, fresh review, explicit
confirmation, rollout checks, and saved request recovery. BookingFlowScreen
renders state and forwards BookingFlowActions without Firestore calls.
AppNavigation passes only the encoded offer ID and keeps Lotteries selected.
Returning to the offer reloads its current saved status.

SavedStateHandle stores primitive draft fields, the accepted policy snapshot, and
an immutable accepted request. Drafts are scoped to the account and offer.
Recreation never submits. A still-matching policy/price acceptance can restore;
changed terms clear it. The accepted request ID/details are saved before networking.
While submitting, duplicate confirmations and edits are blocked.

An uncertain response retains the exact request for **Retry same request**.
**Check request status** queries saved attempts without resubmitting. A saved
requested/confirmed record prevents a second acceptance, including after the
original response was lost. Repository policy/price validation failures return to
selection for refresh and new agreement. A saved rejection can return to refreshed
choices and a new review/attempt ID under the same unexpired offer.

The pending screen observes its own saved request while visible and updates after
confirmation or rejection without resubmitting. Confirmation during an in-flight
submission is retained even if an older pending response arrives afterwards.
Listeners stop when the screen pauses. Reload reconnects a failed listener.
Confirmed requests open the saved receipt; the confirmation Inbox item opens the
existing booking-details screen.

Class relationships: [UML](uml/booking_ui.svg), [editable source](uml/booking_ui.dot).

## Demo setup

The owner's app now processes bookings while open and connected. Submission is
enabled by default, and the standalone GitHub/Cloud Run worker is optional. Deploy
the matching owner-scoped Firestore rules and update both apps in place. See the
[foreground booking guide](local-booking-processing.md) for exact setup, transaction
behavior, permissions, limitations, and verification commands.

Build with `-PbookingRequestsEnabled=false` to keep submission disabled during a
rollout. Tests can still inject either gate value without enabling live writes.

## Troubleshooting submission

“We couldn't verify your request” preserves the accepted request ID and selection
for recovery. It does not mean the request has been confirmed. Check the
request status before retrying that same attempt.

A Firestore `PERMISSION_DENIED` on the acceptance transaction can indicate stale
published rules. The offer update must permit `response: accept` together with
`reservationId`, and the rules must include `acceptedRequest` and `claimedOffer`.
Those checks bind the new requested reservation and its offer claim in one atomic
write. Rules that permit only `response: decline` reject the booking UI's write.
Publish the reviewed booking rules to the app's Firebase project, then retry the
original attempt. The booking-contract rules tests exercise both the batch
contract and the repository's read-then-write transaction.

After successful submission, “Awaiting confirmation” means the request is saved.
Keep the campsite owner's updated app open and online. It will confirm the request,
lock the selected nights, consume the offer, and create the Inbox receipt together.
If processing reports missing permissions, verify the published foreground booking
rules. The pending screen updates from saved server state and never fabricates a
confirmation. A closed owner app leaves the request pending until it reopens.

## Verification

The [foreground booking guide](local-booking-processing.md#verification) lists the
current checks. Existing fake Compose tests cover date selection, policy agreement,
review dismissal, rollout gating, empty availability, and dark mode. Use a separate
disposable test device for instrumentation so the demo account is preserved.

Leave the existing local `.idea/misc.xml` change out of the commit.
