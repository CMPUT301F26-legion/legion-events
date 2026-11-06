# Booking offers

This is the Part 6 implementation for viewing lottery offers and declining an offer
(US 01.04.03). It can be developed and tested before the waitlist and notification
features are available.

The list and decline screens are reachable through **Lotteries → Booking offers → View offers**.
They use `FirebaseOfferRepository.instance` by default. An empty database shows an
empty list; the app does not manufacture demo offers.

## Files and responsibilities

- `model/BookingOffer.kt`: the winning entrant, lottery reference, status, and timestamps.
- `data/OfferRepository.kt`: the interface used by controllers and test doubles.
- `data/FirebaseOfferRepository.kt`: Firebase reads, ownership checks, and the decline transaction.
- `controller/BookingOffersController.kt`: list loading, refresh, retry, and cancellation.
- `controller/BookingOfferController.kt`: detail loading and confirmed decline actions.
- `view/BookingOfferScreen.kt`: list/detail views and decline confirmation.
- `view/LotteriesScreen.kt`: entrant tab with an offers section and an entry-history placeholder.

Navigation reads a single ID through `bookingOfferRoute(offerId)`. This is also the
entry point for Part 5's winning notifications. The list reloads when resumed after
viewing an offer, so a saved decline appears on return. Existing owner and lottery
routes remain available alongside the new routes. The bottom navigation stays
visible on both offer screens with Lotteries selected, including when a notification
opens an offer directly. Back returns to the screen that opened the offer.

The UI uses the app's green theme, rounded outlined cards, shared Back arrow, and
existing text styles in both light and dark mode. Detail decline/reload actions
stay at the bottom above the navigation bar while the content scrolls. The profile
screen no longer contains the offers shortcut. Full lottery-entry history remains
Part 5 work and is explicitly marked as coming soon in its separate section.

The detail controller checks response expiry when confirmation is requested and
submitted, and blocks repeated submissions while a decline is in flight. A failed
action retains the loaded offer with a retry/reload message; an ownership denial
removes the offer from the view. Used, declined, and expired offers have no decline
action. Acceptance, campsite/date selection, and reservation creation are separate work.

The paths above are relative to `app/src/main/java/com/example/legion_events/`.
Use `FirebaseOfferRepository.instance` for the default Firebase app, or pass a
`FirebaseAuth` and `FirebaseFirestore` instance into its constructor. The optional
clock argument supplies device time and makes deadline tests deterministic.

## Stored data

See [SCHEMA.md](../SCHEMA.md#booking-offers) for the field contract. Documents live
at `offers/{offerId}`. The document ID becomes `BookingOffer.id`; it is not
an extra stored field. `userId` is the winner's Firebase Auth UID, not a profile
name, email, or offer ID. Anonymous Firebase accounts have a UID too.

`lotteryId` points to the lottery that supplies the offered campsites and booking
window. Stay dates belong to the later reservation flow. `expiresAt` is a separate
response deadline. The repository maps a Firestore `pending` offer to Open while
its `response` is absent or null, and to Declined once `response` is `decline`.
Invalid records fail instead of being treated as new open offers.

The draw worker will create one offer for each winning user and lottery, using a
stable ID on retries. The Android repository does not create offers or choose
winners. Offer creation must be committed with the draw results by the worker.

## Repository operations

`getOffer(offerId)` checks for a signed-in account before reading. It returns the
owned offer, or null if Firestore reports that the document does not exist. IDs
must identify one document; blank IDs and slash-separated paths are rejected.

`getOffersForCurrentUser()` queries `offers` with
`userId == auth.currentUser.uid`. It returns all matching statuses and an empty
list when no offers match. Every returned document is also checked for ownership.
These two reads use the SDK's default source behavior, which can serve cached
data when the server is unavailable.

`declineOffer(offerId)` checks the current document inside a Firestore transaction:

1. Confirm the document exists and belongs to the authenticated entrant.
2. Validate its stored fields.
3. If already declined, perform no write, even if its deadline has since passed.
4. Otherwise require `pending` status, no response, and `expiresAt > now`.
5. Set `response` to `decline` and `respondedAt` and `updatedAt` to server timestamps.
6. After the transaction succeeds, read from `Source.SERVER` and return the saved offer.

The checks run again if [Firestore retries the transaction](https://firebase.google.com/docs/firestore/manage-data/transactions).
An offer used by another
operation during a retry cannot be overwritten as declined. Transactions require
a server connection. A commit may succeed before a later read or the caller fails;
retrying the method is safe because already declined offers do not receive another
write or timestamp change. This method does not send notifications or draw a replacement.

## Errors and Firebase rules

Signed-out calls, malformed records, missing offers during decline, and invalid
declines raise `IllegalStateException`. Invalid document IDs raise
`IllegalArgumentException`. Ownership mismatches and Firestore `PERMISSION_DENIED`
errors raise `SecurityException`; the latter retains the original error as its
cause. Other Firebase errors and coroutine cancellation propagate to the controller.

Rules can deny a document read before the repository learns whether it exists.
That produces an access error rather than a null result. Controllers should treat
an inaccessible offer as unavailable without revealing whether another user owns it.

Rules and indexes are maintained separately from this app repository. The deployed
offer rules must enforce:

- Active account and ownership for entrant reads. List queries include the user's UID.
- Only an owned, unanswered `pending` offer can be declined through the client.
- Updates change only `response`, `respondedAt`, and `updatedAt`.
- Both timestamps equal `request.time`, and the response deadline has not passed according
  to [request.time](https://firebase.google.com/docs/reference/rules/rules.firestore.Request#time).
  The device clock check is for app behavior, not authoritative enforcement.
- Client creation and deletion of offers are denied; the trusted draw worker creates them.

The Kotlin ownership checks do not replace these rules. This change does not deploy rules.

## Verification

Run the offer tests and the project checks from the repository root:

```sh
./gradlew :app:testDebugUnitTest --tests '*FirebaseOfferRepositoryTest'
./gradlew :app:testDebugUnitTest --tests '*BookingOfferControllerTest' --tests '*BookingOffersControllerTest'
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-configuration-cache :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.legion_events.BookingOfferNavigationTest
./gradlew :app:dokkaGeneratePublicationHtml
```

The unit tests use Mockito at Firebase SDK boundaries and fixed timestamps. They
cover signed-out access, owner checks, mapping, malformed data, queries, status and
expiry restrictions, idempotency, a simulated transaction callback retry, and a
failed read after a successful commit. They do not contact Firebase or validate
deployed rules. Mockito is a local test dependency, explicitly attached as a Java
agent for JVM tests; it is not included in the Android app.

The controller tests use in-memory fake repositories, synthetic UIDs/offer IDs,
fixed timestamps, and delayed responses. They cover loading, newest-first ordering,
empty lists, retry, confirmation/cancellation, duplicate-submit prevention, expired
and terminal offers, uncertain decline outcomes, ownership denial, and cancellation
when a ViewModel is cleared. These tests do not exercise Firebase authentication,
networking, or rules.

`BookingOfferNavigationTest` runs the real Compose Lotteries/list/detail routes with
fake account, profile, campsite, and offer repositories. It also injects owner and
lottery repositories that reject unexpected calls, keeping the newly added routes
isolated from real Firebase access. It covers encoded offer IDs, cancellation and
confirmed decline, saved-state restoration, list refresh on return, read/action
failures, empty lists, missing offers, expiry, selected bottom navigation, and switching
between Profile and an offer in dark mode. It also checks that Profile has no offers
shortcut. Run it on an Android emulator; it does not
create or change Firestore documents. No Gradle dependency or production-data switch
is needed to move these screens out of a testing environment.

For visual review, add `-Pandroid.testInstrumentationRunnerArguments.captureOfferScreens=true`
to that test command. It saves light-mode overview/list/detail/empty-list and dark-mode
detail PNGs in the debug app's cache, using only synthetic test offers. These previews
are not production app data and should not be committed as source assets.

### Run the Firebase decline integration test on a phone

Use a debug app configured for a designated Firebase test project with the team's
Firestore rules. Connect one phone with USB debugging enabled and disconnect
other test devices. This opt-in test creates a separate anonymous test user and
profile during setup. It declines only a seeded offer owned by that user.

In PowerShell, set `$offerTestProject` to the configured test project's ID. Build
and install both APKs, then run the setup method. Copy the
`Booking offer test UID` from the instrumentation output.

```powershell
$offerTestProject = 'your-firebase-test-project-id'
$offerTestClass = 'com.example.legion_events.BookingOfferFirebaseIntegrationTest'
$offerTestRunner = 'com.example.legion_events.test/androidx.test.runner.AndroidJUnitRunner'
$offerAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
& $offerAdb install -r -t app/build/outputs/apk/debug/app-debug.apk
& $offerAdb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $offerAdb shell am instrument -w -e class "${offerTestClass}#prepareTestUser" -e verifyFirebaseOfferDecline true -e offerTestProject $offerTestProject $offerTestRunner
```

In that project's Firestore console, create `offers/decline-test-01`. Set
`userId` to the printed UID, `lotteryId` to a test lottery's ID, and `status` to
`pending`. Leave `response` and `respondedAt` absent. Set `createdAt` and
`updatedAt` to the current time as Firestore timestamps and `expiresAt` to a
future timestamp. On the same device, run:

```powershell
& $offerAdb shell am instrument -w -e class "${offerTestClass}#decliningOwnedOfferPersistsAndRetryPreservesTimestamp" -e verifyFirebaseOfferDecline true -e offerTestProject $offerTestProject -e offerTestId decline-test-01 $offerTestRunner
```

The test checks that the owned open offer appears in the list, that a decline is
saved with a new server timestamp, and that repeating the decline leaves the
document unchanged. It leaves the fixture declined so you can verify it in the
console. Seed a fresh `decline-test-*` document for each new run.

## Remaining integration work

- Part 5's inbox should pass the winning notification's offer ID to
  `bookingOfferRoute(offerId)`. Do not pass an owner UID or serialized offer object.
- The trusted draw backend must create real winning offers using the shared schema.
  This UI PR does not supply automatic draws, win/loss notifications, or replacement draws.
- Confirm the deployed Firestore rules enforce the ownership and decline restrictions
  above; Kotlin checks and fake tests do not validate those rules.

Verify the Firebase integration with the team's rules in an isolated Firebase
emulator or approved test project. Seed an owned open offer, another
user's offer, and used, expired, and declined offers. Check that list reads exclude
the other user, declines persist, a repeated decline preserves `updatedAt`, and
concurrent attempts cannot overwrite an already used offer. Do not run these
checks against shared production data.

Generated KDoc HTML is under `doc/api/` and remains ignored by Git. Commit Kotlin
KDoc and this guide, rather than generated HTML.
