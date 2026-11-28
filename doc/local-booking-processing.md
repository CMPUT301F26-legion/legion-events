# Foreground booking processing

The demo processes reservations while the campsite owner's Android app is open and
connected. It uses the owner's normal Firebase login, matching the existing local
lottery draw. No GitHub job, Cloud Run service, service-account key, or separate
owner approval is required. The entrant must explicitly confirm the reviewed stay.

## Running the demo

1. Publish the reviewed foreground booking additions in `firestore.rules` to the
   Firebase project used by both devices. Preserve unrelated rules already deployed
   by teammates. Installing an APK does not publish database permissions.
2. Build this branch with `./gradlew :app:assembleDebug`. Submission is enabled by
   default; `-PbookingRequestsEnabled=false` still permits selection/review only.
3. Update both the owner and entrant apps in place. Retain app data so each device
   keeps its existing anonymous Firebase account. Use the account that owns the
   campsite on the owner device.
4. Keep the owner's app open and online on any screen. Its foreground controller
   immediately checks owned records and checks again every five seconds, after
   each previous check completes.
5. The entrant selects dates and guests, agrees to the policies, reviews, and
   confirms. A saved request shows **Confirming your reservation…**. That screen
   listens for the outcome and automatically opens the saved **Reservation
   confirmed** receipt only after the transaction reserves every selected night.
   No separate owner approval is needed. Inbox also contains a booking confirmation
   that opens the booking details.
6. The owner can use **Cancel reservation** on a confirmed booking, confirm the
   cancellation, and optionally give a reason. The entrant receives a cancellation
   notice and the foreground processor releases the dates. This reuses the existing
   owner-rejection storage/cleanup contract (`status: rejected`), displayed as
   **Cancelled**. Unconfirmed requests retain a separate **Reject request** action.

Closing or disconnecting the owner app leaves requests pending. Reopening it
catches up. There is no guaranteed processing while the owner app is closed.
Connection and data/permission failures appear to the owner with Retry; other
independent requests continue. Check the pending screen's **Check request status**
action if its listener needs reconnecting.

## Transactions and recovery

`FirebaseLocalBookingRepository` queries only reservations and lotteries owned by
the current UID. It releases rejected/cancelled stays before checking acceptances,
then processes declines. Pending requests are ordered by server acceptance time
within a sweep. Across different devices, the transaction acquiring the canonical
night locks first wins; this is not a global queue guarantee.

- Confirmation rechecks the acceptance, original window, offered campsite,
  ownership, active entrant/listing, capacity, price, policy agreement, UTC dates,
  all owner-listed nights, and every canonical lock. It commits all nights,
  confirmed status, consumed offer, and deterministic Inbox receipt together.
- Failed availability or business validation rejects the attempt and reopens only
  its matching pending acceptance. The original expiry stays fixed. Another
  selection requires fresh review and a new request ID.
- Owner rejection releases only locks still belonging to that request. It reopens
  a pending acceptance, but a previously consumed offer stays consumed. An old
  rejection cannot clear a newer request's acceptance.
- Confirmed entrant cancellations release matching nights, complete cancellation,
  and save the cancellation receipt atomically. Pending requests remain ineligible
  for entrant cancellation.
- A decline selects one remaining lost, enabled entrant with no previous offer.
  The original manifest, when present, excludes original winners and unrelated
  entrants. The replacement retains the exact original expiry. Immutable markers
  prevent duplicate replacements, including expired/exhausted-pool decisions.

Two owner devices can process concurrently. Every transaction rechecks saved state;
existing locks cannot be overwritten. Checkout is excluded. The 366-night contract
is retained. Account changes stop the controller and reject stale reads/writes.
An already in-flight transaction may finish when the app goes into the background.

## Permissions and trust

Owners remain unable to read entrants' private `users` documents. The intentionally
empty path `lotteries/{lotteryId}/bookingEligibility/{uid}` is a permission-only
probe: a permitted missing read means the account is enabled. Rules consult the
private profile internally. Owners can probe their own account and entrants on
their lottery's waitlist; nobody can list or write these paths. The app probes the
owner first so undeployed permissions cannot be mistaken for a disabled entrant.
Confirmation/replacement writes recheck enabled status in rules to close races.

Rules bind each transition, lock, notice, and immutable marker to the owning UID
and saved request/lottery. Entrants and unrelated owners cannot perform these
operations. As with local draws, this demo trusts the campsite owner as a processor:
rules cannot prove random selection, an exhausted candidate pool, or completeness
of a variable 366-night write set. The bundled transaction enforces those details.
Use the existing trusted backend if processing must resist a modified owner app or
run while the owner's app is closed. No privileged credentials are embedded.

The [standalone worker](booking-worker.md) remains optional. Its transactions share
the same request/lock/marker states, so stale and duplicate events are harmless.
No workflow needs enabling for this demo. This change saves in-app confirmations;
email and background confirmation push delivery are not implemented.

## Verification

Run unit tests, lint, and the debug build:

```sh
./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Run synthetic Firestore rules tests with dependencies already installed:

```sh
pnpm dlx firebase-tools@14.25.1 emulators:exec --only firestore --project demo-local-booking 'cd tests/firestore && node --test *.test.mjs'
```

Coverage includes atomic confirmation, duplicate events, competing bookings, all
366 nights, disabled accounts/private profile isolation, foreign owners, lock
overwrites, released dates, replacement idempotency/expiry, foreground lifecycle,
and live entrant updates. Do not run instrumentation tests against a device whose
anonymous account must be preserved: the test runner can uninstall the target app.
