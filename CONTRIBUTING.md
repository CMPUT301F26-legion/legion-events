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
profile. Deleting removes `users/{uid}` and the anonymous account, then onboarding
opens a new identity. `ProfileController` handles these actions through
`ProfileRepository`, implemented by `FirebaseProfileRepository`. `PolicyScreen`
shows the app policy and guidelines from S01 and S11.

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

## Reservations

The **Bookings** tab opens S10's Active and Past tabs (US 01.06.01/02). A stay is
active while its status is `confirmed` and checkout is in the future, including stays
in progress. Past shows confirmed stays at or after checkout, newest checkout first.
Cancelled, rejected, and pending bookings are excluded from both periods.
`ReservationsController` filters owned bookings and moves stays to Past at checkout
without a database write. Switching tabs reuses the same read; `SavedStateHandle`
retains the selection across recreation and when returning from details.
S10b (`ReservationDetailsScreen`) receives only a reservation ID through `AppNavigation`.
Both routes use `ReservationRepository`, implemented by `FirebaseReservationRepository`.
The list refreshes on resume and offers Refresh/Retry. Details check ownership again.

Booking dates, guests, rules acceptance, and total price come from the saved reservation.
Current campsite names and areas are optional display labels; inaccessible listings do
not hide reservations. This flow reads data only. Cancellation, CSV export, and
ratings are separate stories.

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
