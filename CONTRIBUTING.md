# Contributing

## Structure

- `model/`: data classes and domain values.
- `controller/`: screen state and user actions. Controllers use `ViewModel` for lifecycle support.
- `view/`: separate Compose screen files that render state and call action callbacks.
- `data/`: repository interfaces and Firebase access. Keep database calls out of views.
- `AppNavigation.kt`: the shared router. Register new screens here and pass IDs as route arguments.

Read the CRC cards in `images/crc_cards` and [SCHEMA.md](SCHEMA.md) before changing data behavior.

## Navigation skeleton

S11's "Manage my campsites" opens the owner dashboard (S12) for every user, since
ownership is not a role. Its "Admin dashboard" link (S19) appears only when `role`
is `admin`. Reach other owner and admin screens from those dashboards.

`AppNavigation.kt` has a route constant for every mockup screen (S01 to S20). Screens
not built yet are listed in `PLACEHOLDER_SCREENS` and show a "Coming soon" page.
To build yours, remove its entry there, register it with `composable(...)` wrapped in
`RequireAccount`, and add route arguments if it needs an ID. The bottom bar shows on
the five tab routes: Explore, Lotteries, Inbox, Bookings, and Profile.

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

The search bar's QR scan button and the details screen's lottery-entry button are
disabled placeholders. Neither requests permissions nor changes Firestore data.

## Booking offers

`FirebaseOfferRepository` implements `OfferRepository` for the signed-in entrant's
offers. It checks ownership, maps the shared `BookingOffer` schema, and declines
open offers in a transaction. Views use the interface through a controller.
Offer creation belongs to the draw worker. See [the booking-offer guide](doc/booking-offers.md)
for the method behavior, error handling, Firebase rule requirements, and tests.

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
