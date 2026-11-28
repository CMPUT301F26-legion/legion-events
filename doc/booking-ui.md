# Winner booking selection and review (PR 3)

This connects US 01.04.01, US 01.04.02, and US 02.11.01 to the
[booking repository contract](booking-flow.md) and [trusted worker](booking-worker.md).
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
again. Only the worker can confirm the request or reserve nights.

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

Basic manual status recovery is included so this flow is usable and safe. PR 4
still owns automatic status tracking, richer pending/rejected recovery, the
confirmation Inbox type/navigation, and the final confirmation-screen handoff.
Confirmed requests can already open the existing booking-details screen.

Class relationships: [UML](uml/booking_ui.svg), [editable source](uml/booking_ui.dot).

## Worker activation and submission gate

The Firestore rules have been deployed, but the worker still needs activation.
The new UI is connected for selection/review; **submission defaults to disabled**.
The product displays that reservations are not available yet, without exposing
deployment or build details in the entrant flow.

The simplest worker setup after PR 2 merges into main:

1. Have the team's Google Cloud administrator provision the existing Google OIDC
   federation and a service account with Firestore read/write access.
2. In GitHub repository **Settings → Secrets and variables → Actions → Variables**,
   configure FIREBASE_PROJECT_ID, GCP_WORKLOAD_IDENTITY_PROVIDER, and
   GCP_LOTTERY_WORKER_SERVICE_ACCOUNT with that setup's values.
3. Set repository variable BOOKING_PROCESSING_ENABLED to true.
4. Under **Actions → Booking worker**, run the workflow on main and verify success.
   The same workflow is scheduled every five minutes. It intentionally skips
   feature branches and runs only when the enable variable is true.

Setting the enable variable alone does not provision Google credentials or IAM.
The separate private Cloud Run service with Eventarc and a one-minute Scheduler
sweep is the faster processing option; its concrete setup is in the worker guide.
No backend deployment, repository-variable change, or live booking occurs in PR 3.

After the team verifies that the worker processes test-project bookings correctly,
build the Android app with submission enabled:

```sh
./gradlew :app:assembleDebug -PbookingRequestsEnabled=true
```

This sets BuildConfig.BOOKING_REQUESTS_ENABLED. Without the property it is false.
Android Studio can read the same property from the developer's local Gradle
configuration. Tests inject the enabled/disabled value without enabling live writes.
No runtime setting in the Android app starts the backend.

## Verification

Local JVM tests cover date gaps, occupied checkout boundaries, past check-in,
capacity, CAD arithmetic, time zones, the 366-night maximum, explicit review,
policy/price changes, concurrent clicks, expiry, rollout, lost responses, and
saved-state retry behavior. Compose tests use only fake repositories and accounts.
The navigation regression verifies encoded offer IDs and the Lotteries tab.

```sh
./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew --offline --no-configuration-cache :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.legion_events.BookingFlowScreenTest
./gradlew --offline --no-configuration-cache :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.legion_events.BookingOfferNavigationTest
```

Local verification passed 369 JVM tests, with four optional pre-existing integration
tests skipped, and all 11 booking UI/navigation tests on the connected Android 17
emulator. The debug APK built successfully; lint reported zero errors and 25
existing warnings. The default APK has BOOKING_REQUESTS_ENABLED=false.

The UI tests exercise real date pickers, policy agreement, review dismissal,
pending submission, the disabled rollout gate, empty availability, and dark mode.
Disable the configuration cache when changing instrumentation runner arguments
so the Android plugin does not reuse an earlier test-class filter.
Optional preview capture uses instrumentation argument captureBookingScreens=true
and writes fake-data PNGs only into the app's cache.

Keep this PR based on feature/booking-worker while PR 2 is pending. The worker,
rules, and repository contract files remain unchanged; no Figma files were edited.
Leave the existing local .idea/misc.xml change out of the commit.
