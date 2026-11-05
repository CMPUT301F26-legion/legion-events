# Booking offers

This is the Part 6 implementation for viewing lottery offers and declining an offer
(US 01.04.03). It can be developed and tested before the waitlist and notification
features are available.

The list and decline screens are reachable through **Profile → Lottery offers**.
They use `FirebaseOfferRepository.instance` by default. An empty database shows an
empty list; the app does not manufacture demo offers.

## Files and responsibilities

- `model/BookingOffer.kt`: the winning entrant, lottery reference, status, and timestamps.
- `data/OfferRepository.kt`: the interface used by controllers and test doubles.
- `data/FirebaseOfferRepository.kt`: Firebase reads, ownership checks, and the decline transaction.
- `controller/BookingOffersController.kt`: list loading, refresh, retry, and cancellation.
- `controller/BookingOfferController.kt`: detail loading and confirmed decline actions.
- `view/BookingOfferScreen.kt`: list/detail views and decline confirmation.

Navigation reads a single ID through `bookingOfferRoute(offerId)`. This is also the
entry point for Part 5's winning notifications. The list reloads when resumed after
viewing an offer, so a saved decline appears on return. Existing owner and lottery
routes remain available alongside the new routes.

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
at `bookingOffers/{offerId}`. The document ID becomes `BookingOffer.id`; it is not
an extra stored field. `userId` is the winner's Firebase Auth UID, not a profile
name, email, or offer ID. Anonymous Firebase accounts have a UID too.

`lotteryId` points to the lottery that supplies the offered campsites and booking
window. Stay dates belong to the later reservation flow. `expiresAt` is a separate
response deadline. Null or missing optional
timestamps are accepted. Required text must be nonblank, status must be one of
`open`, `used`, `declined`, or `expired`, and timestamp fields must contain timestamps.
Invalid records fail instead of being treated as new open offers.

The draw worker will create one offer for each winning user and lottery, using a
stable ID on retries. The Android repository does not create offers or choose
winners. Offer creation must be committed with the draw results by the worker.

## Repository operations

`getOffer(offerId)` checks for a signed-in account before reading. It returns the
owned offer, or null if Firestore reports that the document does not exist. IDs
must identify one document; blank IDs and slash-separated paths are rejected.

`getOffersForCurrentUser()` queries `bookingOffers` with
`userId == auth.currentUser.uid`. It returns all matching statuses and an empty
list when no offers match. Every returned document is also checked for ownership.
These two reads use the SDK's default source behavior, which can serve cached
data when the server is unavailable.

`declineOffer(offerId)` checks the current document inside a Firestore transaction:

1. Confirm the document exists and belongs to the authenticated entrant.
2. Validate its stored fields.
3. If already declined, perform no write, even if its deadline has since passed.
4. Otherwise require `open` status and either no deadline or `expiresAt > now`.
5. Set only `status` to `declined` and `updatedAt` to a server timestamp.
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

- Authentication and ownership for reads; list queries include the user's UID.
- Only an owned `open` offer can change to `declined` through the client.
- Updates change only `status` and `updatedAt`, preserving lottery and user IDs.
- `updatedAt` equals `request.time`, and a response deadline has not passed according
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

`BookingOfferNavigationTest` runs the real Compose profile/list/detail routes with
fake account, profile, campsite, and offer repositories. It also injects owner and
lottery repositories that reject unexpected calls, keeping the newly added routes
isolated from real Firebase access. It covers encoded offer IDs, cancellation and
confirmed decline, saved-state restoration, list refresh on return, read/action
failures, missing offers, and expiry. Run it on an Android emulator; it does not
create or change Firestore documents. No Gradle dependency or production-data switch
is needed to move these screens out of a testing environment.

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
