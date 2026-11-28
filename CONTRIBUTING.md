# Contributing

## Structure

- `model/`: data classes and domain values.
- `controller/`: screen state and user actions. Controllers use `ViewModel` for lifecycle support.
- `view/`: separate Compose screen files that render state and call action callbacks.
- `data/`: repository interfaces and Firebase access. Keep database calls out of views.
- `AppNavigation.kt`: the shared router. Register new screens here and pass IDs as route arguments.

Read the CRC cards in `images/crc_cards`, the UML diagrams in `doc/uml`, and
[SCHEMA.md](SCHEMA.md) before changing data behavior.

## Navigation skeleton

S11's "Manage my campsites" opens the owner dashboard (S12) for every user, since
ownership is not a role. Its "Admin dashboard" link (S19) appears only when `role`
is `admin`. Reach other owner and admin screens from those dashboards.

`AppNavigation.kt` has a route constant for every mockup screen (S01 to S20). Screens
not built yet are listed in `PLACEHOLDER_SCREENS` and show a "Coming soon" page.
To build yours, remove its entry there, register it with `composable(...)` wrapped in
`RequireAccount`, and add route arguments if it needs an ID. The bottom bar shows on
the five tab routes: Explore, Lotteries, Inbox, Bookings, and Profile. Offer list and
detail routes also keep this bar visible with Lotteries selected.

## Profile

S01 (`WelcomeScreen`) appears once per device after setup; `WelcomePreferences`
remembers it. S11 (`ProfileScreen`) edits name, email, and phone, sets
`notificationsEnabled` (check it before sending a notification), and deletes the
profile. Deleting removes `users/{uid}` and its current owned profile photo in one
transaction, then deletes the anonymous account. After deletion, onboarding
opens a new identity. `ProfileController` handles these actions through
`ProfileRepository`, implemented by `FirebaseProfileRepository`. `PolicyScreen`
shows the app policy and guidelines from S01 and S11.

### Profile images

S11 offers **Add image**, **Change image**, and **Remove image** (US 01.08.02).
`ProfilePhotoController` manages these actions independently of contact drafts using
the same `ProfileRepository`. The system photo picker is connected in `AppNavigation`;
the shared `encodePhoto` helper resizes and compresses off the main thread. No gallery
or camera permission is requested. `ProfilePhotoSection` renders the saved image and
error/retry state.

`FirebaseProfileRepository` creates an immutable `photos` document with `targetType:
"user"`, changes only `users/{uid}.photoId` and `updatedAt`, and deletes the replaced
owned profile image in one transaction. Removal clears the pointer and deletes the
owned image atomically. Writes check the displayed image ID so a stale retry cannot
replace or delete another session's newer image. **Retry image** repeats a failed
operation; **Reload image** discards it and reads the current image. Recreating the
screen does not automatically submit another image change or reset contact edits.
Loading an image leaves contact Save and Delete available; an image change or
removal temporarily disables them until its write finishes.

## Private messages

US 01.13.02 uses private entrant–owner chat, as distinct from public reviews or
announcements. Start from **Campsite details → Message owner**. Either participant
can reopen a thread through **Profile → Messages** or **Inbox → Messages**. Existing
owner notices and lottery notifications keep their one-way behavior.

`Conversation` and `ChatMessage` model the existing `conversations` and `messages`
collections. `MessageRepository`, implemented by `FirebaseMessageRepository`, checks
membership and derives the sender and receiver from Firebase Auth and the stored
participants. `MessagesController` owns the thread list; `ChatController` owns one
conversation, its draft, and explicit sends. Their separate screen files render
state and callbacks. Register chat entry points only through `AppNavigation`.

The repository queries only conversations where the current UID is the entrant or
owner, and messages for the selected thread. Listeners stop while their screen is
hidden. Draft text and an uncertain send's message ID/body survive recreation;
recreation never sends automatically. **Retry send** reuses that ID, so a lost commit
response does not duplicate a message or reset a newer preview. Messages are limited
to 4000 characters. **Discard draft** releases a failed send so a new message can be
written; it clears only the local draft, since the original may already be delivered.
Each send rechecks conversation membership on the server without fetching its campsite label.
Chat push notifications, read receipts, and paging are separate
work. Existing admin logs continue to show only the owner's direction of messages.

## Campsite details

Tapping a listing opens `CampsiteDetailsScreen` through `AppNavigation`, passing
only its document ID. `CampsiteDetailsController` loads the listing and photos
through `CampsiteDetailsRepository`, implemented by `FirebaseRepository`.
Search text and filters remain on the Home back-stack entry. Keep new detail UI in
the details screen and database reads in the repository. Photos use the schema's
Base64 data and are decoded off the main thread as gallery items become visible.

The search bar's QR button opens S05 (`QrScanScreen`). `QrScanController` validates
results with `CampsiteQr.decode`, and the shared router replaces S05 with S04 after
a successful scan. Back returns to the existing search and filters. The embedded
ZXing camera requests camera permission only when scanning starts, accepts QR codes
only, and saves no camera images. Its visible "Back to search" button cancels the
scan and returns to the existing search. Invalid codes offer Retry; denied permission also
offers app settings. Scanner state survives recreation to avoid duplicate launches.
The details screen's lottery-entry button remains a disabled placeholder.

## Owner campsites

S12 (`OwnerDashboardScreen`), S13 (`CampsiteEditScreen`), and S14 (`OwnerCampsiteScreen`)
use `OwnerCampsiteRepository`, implemented by `FirebaseOwnerCampsiteRepository`. It reads
only campsites whose `ownerId` is the signed-in user, active or not. S12 and S14 reload
each time they resume, so changes saved in S13 appear on return. Each card's lottery
badge comes from the owner's `lotteries` (open, upcoming, awaiting draw, drawn, or none).
The owner bottom bar (Dashboard, Lotteries, Reservations, Notices) shows on S12, S14,
and the owner tab routes.

S13 adds a campsite when opened without `campsiteId` and edits one when given an ID.
Save validates inline against the same limits as the Firestore rules, writes the
listing, adds any new cover photo, then opens S14. If only the photo fails, the form
stays open on the saved campsite so Save retries the photo without creating a duplicate.
Coordinates are required. Edits keep the owner, ratings, and active flag. The open-from and
open-until dates are saved as every night in that range in `availableNights`, at most
366. "Use current location" asks for location permission only when tapped. Photos are
resized and saved as Base64 JPEG, as the schema describes.

S14's QR code holds `legionevents://campsite/{campsiteId}`. Use `CampsiteQr.decode`
in the S05 scanner to read the ID, then open S04. Download saves the code as a PNG;
Share sends it through the Android share sheet using the app's `FileProvider`.

## Booking offers

`FirebaseOfferRepository` implements `OfferRepository` for the signed-in entrant's
offers. It checks ownership, maps the shared `BookingOffer` schema, and declines
open offers in a transaction. Views use the interface through a controller.
Offer creation belongs to the draw worker. See [the booking-offer guide](doc/booking-offers.md)
for the method behavior, error handling, Firebase rule requirements, and tests.
The list and decline screens are under **Lotteries → Booking offers → View offers**. Controllers
use the real repository by default; fake offers are supplied only by tests.
Winning notifications can open an offer through `bookingOfferRoute(offerId)` when
Part 5's inbox is implemented. Pass only the document ID; the repository checks ownership.
`LotteriesScreen` reserves a separate Lottery entries section for Part 5; it does
not treat winning offers as the user's full entry history. Offer views use the
shared theme, outlined cards, and Back arrow, with decline/reload actions above
the bottom navigation. Keep notification navigation compatible with direct offer links.

BookingRepository / FirebaseBookingRepository provide current choices, explicit
acceptance, and request recovery. BookingRequest, BookingOptions, and BookedNight
define dates, price, policy, and lock contracts. BookingFlowController uses these
contracts for selection, policy agreement, fresh review, and explicit submission;
see [booking UI](doc/booking-ui.md). Submission defaults off until the compatible
worker is operational; build with `-PbookingRequestsEnabled=true` only after
verifying that setup.
See [booking flow](doc/booking-flow.md) for lifecycle, rollout, and checks.

## Reservations

The **Bookings** tab opens S10's Active, Past, and Cancelled tabs. A stay is
active while its status is `confirmed` and checkout is in the future, including stays
in progress. Past shows confirmed stays at or after checkout, newest checkout first.
Rejected and unconfirmed bookings are excluded from both. Completed cancellations and
owner-cancelled (`rejected`) bookings appear in Cancelled, newest first, so their receipts
remain accessible. Check-in and checkout are shown as UTC calendar days, matching S17.
`ReservationsController` filters owned bookings and moves stays to Past at checkout
without a database write. Switching tabs reuses the same read; `SavedStateHandle`
retains the selection across recreation and when returning from details.
S10b (`ReservationDetailsScreen`) receives only a reservation ID through `AppNavigation`.
Both routes use `ReservationRepository`, implemented by `FirebaseReservationRepository`.
The list refreshes on resume and offers Refresh/Retry. Details check ownership again.

Booking dates, guests, rules acceptance, and total price come from the saved reservation.
Current campsite names and areas are optional display labels; inaccessible listings do
not hide reservations.

### Rate a completed stay

**Bookings → Past → View reservation → Rate your stay** opens S10c (US 01.13.01).
`rateStayRoute(reservationId)` passes only the booking ID. `RateStayController`
loads the eligible stay and existing score through `RatingRepository`, implemented
by `FirebaseRatingRepository`; `RateStayScreen` renders state and action callbacks.

A stay can be rated at or after checkout while still `confirmed`, with no pending
cancellation, and while its campsite is available. The repository checks ownership
and eligibility again inside the save transaction. Choose one to five stars and
save, or use **Maybe later** without writing. An existing campsite rating is prefilled
and can be updated. A successful save returns to Bookings and preserves the selected
tab. The draft survives recreation; loading and saving errors offer separate retries.

Ratings use `ratings/{campsiteId}_{uid}`, so repeat stays share one score. Creates
use server timestamps; updates change only `score` and `updatedAt`. Retrying the
same score does not rewrite timestamps. The current rules enforce the signed-in
UID, field contract, and score range; completed-stay eligibility is additionally
checked by the app repository. Stored campsite `ratingAverage` and `ratingCount`
remain backend-managed and need trusted aggregation to reflect new scores.

### Cancellation

S10b asks for confirmation before submitting a cancellation (US 01.07.01). The
client transaction changes only `cancellationRequested` and `updatedAt`; it never
changes status or deletes occupied nights. Requests are allowed before checkout,
including during a stay. Retrying an already requested or completed cancellation
does not rewrite it.

While a request is pending, the stay remains confirmed and its dates reserved.
The details controller checks status every five seconds while its screen is resumed;
it stops when hidden or complete. Refresh recovers a failed check or uncertain request.
Only the worker's committed `cancelled` status with a cleared request flag shows
completion. The same transaction releases owned nights and saves the Inbox notice.
The notice opens S09; Cancelled bookings also open S10b and their confirmation/CSV
(US 01.07.02). See [worker commands and setup](doc/reservation-cancellation.md).

### Confirmation and CSV

S10b offers confirmation and CSV actions for confirmed bookings (including past
stays) and completed cancellations. S09 (`ReservationConfirmationScreen`) shows the
saved booking number, campsite, dates, guests, and total. Booking creation should
call `reservationConfirmationRoute(reservationId)` after the worker confirms the reservation;
pass only its ID. S09 checks ownership and status before showing confirmation.

`ReservationDetailsController` supports both screens and rechecks the owned booking
before each export. `Reservation.confirmationCsv` and `cancellationCsv` format the receipts;
`writeReservationCsv` writes UTF-8 on the IO dispatcher. The shared router opens
Android's Save dialog (`text/csv`), so no storage permission is needed. Cancelling
does nothing; a failed save leaves Download available to retry. A recreated screen
does not repeat a save automatically.

The CSV contains the reservation and campsite IDs, optional current listing name
and area, UTC check-in and checkout, guests, exact CAD total, currency, saved status,
and rules acceptance. Dates use millisecond precision and checkout remains excluded.
Cells are quoted, quotes are escaped, and formula-like text is exported as text.
No profile information is included. A cancellation CSV adds `cancelledAtUtc` from
the worker-written `updatedAt`. A pending request cannot produce a cancellation
receipt. Export rechecks the status after the Save dialog; it does not silently
change a selected booking receipt into a cancellation receipt. Booking creation
and booking notification integration remain follow-up work described in [booking flow](doc/booking-flow.md).

## Owner reservations

S17 (`OwnerReservationsScreen`) is the owner bar's **Reservations** tab. A dropdown picks one of the
owner's campsites (from `OwnerCampsiteRepository.getMyCampsites`), and `OwnerReservationsController`
reads that campsite's bookings through `OwnerReservationRepository`, implemented by
`FirebaseOwnerReservationRepository`. It reloads on resume and offers Refresh/Retry. The campsite,
tab, and calendar month are kept in `SavedStateHandle`.

- **Calendar (US 02.06.01):** fills every night held by a `confirmed` booking and outlines nights
  asked for by upcoming `requested` ones, using `Reservation.bookedNights()` (UTC `YYYY-MM-DD`,
  checkout excluded). It opens on the next stay's month, or the current month. `BookingCalendar.kt`
  lays out the grid.
- **Tabs (US 02.07.01 to 02.07.03):** Active lists upcoming and ongoing `requested` and `confirmed`
  bookings (`Reservation.isUpcomingForOwner`), because entrant bookings start as `requested`; a status
  badge tells them apart. Past uses S10's rule (`confirmed` after checkout). Cancelled lists
  `cancelled` and `rejected` bookings, most recently changed first. Switching tabs does not read again.
- **View booking:** a dialog with the reservation number, dates, guests, total, and rules acceptance.
  The rules let only a profile's owner or an admin read `users/{uid}`, so entrant names are not shown.
- **Confirmation:** the entrant's explicit acceptance is the approval. Only the trusted worker
  confirms a reservation, locks its nights, and consumes its offer. Owners monitor requested
  and confirmed bookings; there is no owner Accept action. See [booking contracts](doc/booking-flow.md).
- **Reject (US 02.08.01):** only bookings in the Active tab (`requested` or `confirmed`, before checkout). The dialog takes an optional reason (at most
  `Reservation.MAX_REJECTION_REASON_LENGTH`). One transaction sets the booking to `rejected` and writes an
  `ownerNotices` entry to that entrant, so it appears in their S07 inbox; the booking then moves to
  Cancelled. The app never writes `bookedNights` (the rules make them worker-only), so a rejected
  booking's nights are not released until the worker handles `rejected` bookings.
- **Denied writes:** if Firestore denies a rejection, S17 shows "You don't have permission to
  reject this reservation." and logs the original error under the `OwnerReservations` tag
  (`adb logcat -s OwnerReservations`). The usual cause is a booking whose `lotteryId` is not one of
  the owner's lotteries.

Card and calendar dates are UTC days so they match `availableNights` and the lottery date pickers.
The shared `toReservation()` in `ReservationDocument.kt` maps booking documents for both the entrant
and owner repositories; call it only after checking who may read the record.

## Theme

The app-wide **Dark mode** switch selects light or dark appearance. Light is the
default, and the choice is saved on the device across app restarts.

Define shared colors in `ui/theme/Color.kt` and map their roles in `ui/theme/Theme.kt`.
Light mode uses moderately saturated fern-green accents, pure white backgrounds,
and neutral gray controls. Dark mode uses charcoal surfaces with brighter green
accents from the same family. The root
`LegioneventsTheme` applies the selected palette to every screen.

Use `MaterialTheme.colorScheme` roles such as `primary`, `background`, `surface`,
and `onSurface` in components. Standard Material controls inherit these colors;
avoid hardcoded colors or separate light/dark checks in each component. Keep
preference storage in `ThemePreferences`, actions in `ThemeController`, and the
switch UI in `ThemeToggle`.

## Accounts and ownership

Account roles are `user` and `admin`. Ordinary users share the same capabilities.
A listing belongs to the user identified by its `ownerId`; ownership is not a
separate account role. Users can view and create listings and modify only their own
listings. Admins have additional permissions. An entrant is a participant in a lottery.

## Firestore rules

The deployed security rules are kept in [`firestore.rules`](firestore.rules). Change rules there,
in the same pull request as the app code that needs them, so reviewers see both. After the pull
request is merged, deploy it by pasting the file into the Firebase console (**Firestore Database →
Rules → Publish**), or with the Firebase CLI: `firebase deploy --only firestore:rules`. The rules
are shared by everyone using the project, so tell the team before publishing. If someone edits the
rules in the console, copy them back into `firestore.rules` so the file stays accurate.

## Changes and documentation

Keep each commit focused on one small feature. Separate unrelated changes and
avoid adding future functionality to the same commit.

Add simple KDoc to model, controller, and view functions, including private helpers.
Use `@param`, `@return`, and `@property` where useful. Generate HTML locally when
needed; `doc/api/` is ignored and should not be committed or edited by hand.

## Checks

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:dokkaGeneratePublicationHtml
```

Open `doc/api/index.html` to read the generated documentation. Use an isolated
emulator for device tests and keep production credentials and infrastructure out of app changes.

To check private-chat queries and atomic profile/photo deletion against the
checked-in Firestore rules, start a local demo emulator in another terminal:

```sh
firebase emulators:start --only firestore --project demo-legion-chat-review
```

Then run the rules tests (use the port printed by the emulator):

```sh
CHAT_RULES_EMULATOR_HOST=127.0.0.1:8080 ./gradlew :app:testDebugUnitTest --tests 'com.example.legion_events.data.ProfileChatRulesIntegrationTest'
```

These tests load `firestore.rules` into a loopback emulator with isolated fixtures.
They run only against `demo-` projects and are skipped when the host variable is absent.
