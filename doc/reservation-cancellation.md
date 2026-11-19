# Reservation cancellation

US 01.07.01 submits a cancellation request. US 01.07.02 shows its completed
confirmation in Inbox and provides a downloadable CSV.

## Flow

1. In **Bookings → Active → View reservation**, choose **Cancel reservation**.
   Review the dialog and choose **Request cancellation** or **Keep reservation**.
2. The client transaction checks the Firebase UID, current status, and checkout.
   It sets only `cancellationRequested: true` and the server `updatedAt`.
3. `ReservationCancellationWorker` rechecks each requested confirmed booking.
   In one transaction it deletes the booking's `bookedNights`, writes `cancelled`,
   clears the request flag, updates server time, and creates one cancellation notice.
4. S10b checks pending status while visible. After completion, the booking appears
   under **Cancelled** and its Inbox notice opens S09. Either screen can download
   the cancellation CSV using Android's Save dialog.

Requests remain pending until a worker run completes them. A delayed worker still
honours an accepted request after checkout. Retrying a request or running workers
concurrently does not duplicate cancellation or reset its timestamp. The worker
never deletes a night assigned to another reservation. A failed transaction leaves
the booking, nights, and notice unchanged; other requests are still attempted.
At most 498 occupied-night documents can be released per transaction, leaving
room for the reservation update and notification within the write limit.

The cancellation CSV retains the original saved dates, guests, and CAD total;
these are booking details, not a refund calculation. `cancelledAtUtc` comes from
the worker's server `updatedAt`. A request flag alone cannot generate this receipt.
Cancellation notices are saved to Inbox; this step does not send FCM pushes.

## Worker setup

The existing [worker setup](lottery-worker.md) supplies the Admin SDK connection
and GitHub Actions identity. The deployed rules must permit an active user to
update only their own request flag and server update time. Clients must not set
reservation status, delete occupied nights, or create cancellation notices.

To enable processing in the existing **Lottery draw** workflow after review and
merge, set the repository variable `RESERVATION_CANCELLATIONS_ENABLED` to `true`.
It defaults to off and works independently of `LOTTERY_DRAW_ENABLED`. The existing
hourly schedule or a manual workflow run processes all pending requests. This
setting uses the existing project and worker identity; no new credentials are needed.

For a local emulator run from the repository root:

```sh
FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 ./gradlew -p lottery-worker test
FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_PROJECT_ID=demo-legion-cancellation \
  ./gradlew -p lottery-worker run --args=process-cancellations
```

Live command-line runs require the existing Application Default Credentials,
`FIREBASE_PROJECT_ID`, and `RESERVATION_CANCELLATIONS_ENABLED=true`. No live
processing or rule deployment is needed for emulator verification.

## Responsibilities and tests

- `ReservationRepository`: owned reads and idempotent request submission.
- `ReservationDetailsController`: consent, request state, visible status checks,
  and receipt actions. `AppNavigation` owns routes and Android Save intents.
- `ReservationCancellationWorker`: trusted atomic release and notification creation.
- `Reservation.cancellationCsv`: receipt values and CSV formatting without IO.

App unit tests cover ownership, request retries, checkout, consent, lifecycle
checks, and receipt eligibility. Android tests cover pending and completed UI,
Inbox routing, and file output. Worker tests use isolated demo projects in the
Firestore emulator to cover atomicity, concurrent runs, rollback, and write limits.
