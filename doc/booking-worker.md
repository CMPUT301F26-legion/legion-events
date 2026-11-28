# Booking worker (PR 2)

The demo now uses the [foreground owner processor](local-booking-processing.md). This standalone backend remains optional; no GitHub worker needs enabling for foreground processing.

This implements the trusted backend for the [PR 1 booking contract](booking-flow.md).
It adds no Android screens. Submission, review, and rules acceptance are connected
in PR 3; pending/rejected recovery and confirmation Inbox handling follow in PR 4.
The existing foreground Android lottery draw can continue to issue offers while
this separate backend handles booking acceptance and declines.

## Processing

| Processor | Input | Atomic result |
| --- | --- | --- |
| `ReservationConfirmationWorker` | `requested` reservation with its matching pending offer acceptance | Validate; create every occupied-night lock; confirm reservation; mark offer `used`; publish confirmation |
| `ReservationRejectionWorker` | `rejected` reservation without a release marker | Release only its own locks; reopen a matching pending acceptance; save release marker |
| `OfferReplacementWorker` | Pending offer with explicit `decline` and no replacement marker | Choose at most one eligible remaining entrant; publish their offer and result; save replacement marker |

Winning alone never creates a reservation. The entrant's explicit acceptance is
the required approval; owners have no separate acceptance step. Confirmation
checks identity bindings, active account/listing, original lottery and offered
campsites, guest capacity, integer CAD cents total, `rulesAccepted`, whole UTC
dates, contiguous owner-listed nights, and the absence of **every** canonical lock.
Checkout is excluded and stays are bounded to 366 nights. The server acceptance
timestamp must match the offer's `respondedAt` and fall inside the original
submission window. A timely request can finish processing after that window closes.

An availability or business-validation failure rejects only that attempt and
reopens the same offer in the same transaction. It creates no locks or success
notice and keeps the original expiry. A fresh reviewed attempt needs a new request
ID. Missing or malformed structural data instead reports an error and remains
unconfirmed for repair; other independent requests still run.

An owner rejection of a **pending** request also permits a fresh reviewed attempt
under the same unexpired offer. Rejection of an already confirmed reservation
releases its nights but retains the consumed offer. Cleanup of an old rejected
attempt cannot clear a newer acceptance. Entrant cancellation remains the separate
existing `process-cancellations` command and is available only after confirmation.

Replacements exclude everyone with an existing offer, a winning entry, an original
winning-manifest entry, or a confirmed booking. Candidates must still have a lost
waitlist entry and an existing, enabled account. With an initial Android draw
manifest, they must also belong to that original entrant set; older cloud draws
without a manifest use the saved waitlist. Selection is random among eligible
users, within the existing 100-entry bound. Other winners, reservations, the
initial draw manifest, `winnersDrawn`, and the original booking deadline stay intact.
An expired offer or an exhausted eligible pool gets a null replacement marker;
later runs do not redraw it. Declines of replacement offers can produce one further
replacement from the remaining pool.

All reads precede writes in each transaction. Concurrent requests cannot both
acquire the same night, and a conflicting write rolls back the entire result.
Stable state checks and marker IDs make repeated events and sweeps safe. See
[Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions).

## Documents and notifications

- Locks: `bookedNights/{campsiteId}_{YYYY-MM-DD}`, with `campsiteId`, `reservationId`, `date`.
- Confirmation: `notifications/reservation_confirmed_{reservationId}`, using the
  PR 1 `reservation_confirmed` payload and the saved reservation ID as its reference.
  The reservation, locks, consumed offer, and notice commit together.
- Rejection marker: `reservationReleases/{reservationId}`, with `reservationId`
  and server `processedAt`. The existing owner action already publishes its notice.
- Replacement marker: `offerReplacements/{declinedOfferId}`, with `lotteryId`,
  `declinedOfferId`, nullable `replacementUserId`/`replacementOfferId`, and server `processedAt`.
- Replacement result: `notifications/lottery_replacement_{declinedOfferId}` and a
  matching `lotteryNotificationOutbox` document. It uses the normal `lottery_won`
  payload and new offer ID, so the current winning-offer Inbox route and optional
  FCM delivery work. Original loss notices remain as history.

The current rules already keep both marker collections private and all worker
writes privileged. No rules or Android contracts are changed here. The confirmation
notice is stored now; displaying/opening that notice in the Android Inbox is PR 4.
This worker does not send confirmation email or confirmation FCM messages.

Class relationships: [UML](uml/booking_worker.svg), [editable source](uml/booking_worker.dot).

## Commands and local verification

Run from the repository root with the standalone worker's Java 25 toolchain:

```sh
./gradlew -p lottery-worker test installDist
```

Worker tests require a loopback Firestore emulator and otherwise skip. For a full
run with isolated synthetic demo projects, install the existing rules-test packages
first (`pnpm install --frozen-lockfile --ignore-scripts` inside `tests/firestore`), then:

```sh
pnpm dlx firebase-tools@14.25.1 emulators:exec --only firestore --project demo-booking-worker './gradlew -p lottery-worker test installDist && cd tests/firestore && node --test *.test.mjs'
```

Use `node --test *.test.mjs` to include this PR's new `booking-worker.test.mjs`;
PR 1's explicit `pnpm test` list does not include that new file.
The full run passes **47 worker integration tests** and **66 rules tests**, with
zero skips. Coverage includes simultaneous overlapping bookings, duplicate events,
timer recovery, expiry, invalid requests, atomic rollback, 366-night stays,
pending-owner-rejection retries, lock ownership, exhausted replacement pools,
competing declines, replacement chains, and private markers.

For manual emulator processing, start the emulator in another terminal, then:

```sh
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
export FIREBASE_PROJECT_ID=demo-booking-manual
./gradlew -p lottery-worker run --args=process-bookings
# Or listen on PORT (default 8080; choose another port beside the emulator):
PORT=8081 ./gradlew -p lottery-worker run --args=serve-bookings
```

`process-bookings` performs one sweep: rejection cleanup, confirmations, then
decline replacements. `serve-bookings` exposes POST `/events` and POST `/sweep`.
The listener validates Firestore CloudEvent project, type, and document path and
rereads the current record. `/events` accepts only `reservations/{id}` and
`offers/{id}` writes. Completed/no-op work returns 200, invalid events return 400,
and processing failures return 500 for retry. A sweep attempts other records and
stages before reporting aggregate failures. Errors retain document IDs in backend
logs; request responses contain no booking data.

## Runtime activation after review

Local editing needs no further setup. Nothing in this PR deploys a service, enables
a repository variable, changes live data, or activates a timer. Live commands
`process-bookings` and `serve-bookings` both require `BOOKING_PROCESSING_ENABLED=true`
plus `FIREBASE_PROJECT_ID` and Application Default Credentials. The draw enable flag
does not enable booking processing. Demo emulator runs bypass live enable flags.

For prompt processing, deploy a **separate, IAM-authenticated Cloud Run service**
with `serve-bookings`, two Firestore write triggers, and a one-minute recovery timer.
The Cloud Run IAM gateway supplies authentication; CloudEvent headers alone do not.
Keep the existing draw service and cancellation command configured as before.
The Dockerfile still defaults to `serve`; its split ENTRYPOINT/CMD allows the new
service to override arguments with `serve-bookings`.

The team's Cloud project administrator can follow the source-build, API, and
runtime/caller identity setup in [the existing deployment guide](lottery-deadline-automation.md),
using separate service/trigger/job names for bookings. The runtime identity needs
Firestore read/write access; the event/timer caller needs Eventarc receive and
Cloud Run invoke permissions. Use a team test project first. After building
`installDist`, the booking-specific deployment shape is:

```sh
# Set these to the team's test project, chosen region, and provisioned identities.
booking_project=REPLACE_WITH_TEST_PROJECT_ID
booking_region=us-central1
booking_database_location=REPLACE_WITH_FIRESTORE_LOCATION
booking_runtime=REPLACE_WITH_RUNTIME_SERVICE_ACCOUNT_EMAIL
booking_caller=REPLACE_WITH_TRIGGER_SERVICE_ACCOUNT_EMAIL

(cd lottery-worker && gcloud run deploy legion-booking-automation \
  --source=. --project="$booking_project" --region="$booking_region" \
  --service-account="$booking_runtime" --args=serve-bookings \
  --set-env-vars="FIREBASE_PROJECT_ID=$booking_project,BOOKING_PROCESSING_ENABLED=true" \
  --no-allow-unauthenticated --concurrency=4 --timeout=120 --memory=512Mi --max-instances=3)

gcloud run services add-iam-policy-binding legion-booking-automation \
  --project="$booking_project" --region="$booking_region" \
  --member="serviceAccount:$booking_caller" --role=roles/run.invoker

for booking_collection in reservations offers; do
  gcloud eventarc triggers create "booking-$booking_collection-writes" \
    --project="$booking_project" --location="$booking_database_location" \
    --destination-run-service=legion-booking-automation \
    --destination-run-region="$booking_region" --destination-run-path=/events \
    --event-filters='type=google.cloud.firestore.document.v1.written' \
    --event-filters='database=(default)' \
    --event-filters-path-pattern="document=$booking_collection/{id}" \
    --event-data-content-type=application/protobuf --service-account="$booking_caller"
done

booking_url=$(gcloud run services describe legion-booking-automation \
  --project="$booking_project" --region="$booking_region" --format='value(status.url)')
gcloud scheduler jobs create http booking-sweep \
  --project="$booking_project" --location="$booking_region" \
  --schedule='* * * * *' --time-zone=UTC --uri="$booking_url/sweep" --http-method=POST \
  --oidc-service-account-email="$booking_caller" --oidc-token-audience="$booking_url" \
  --attempt-deadline=120s --max-retry-attempts=3
```

Eventarc uses the Firestore database location, which can differ from the Cloud Run
region. The path filters and protobuf format follow
[Firestore event routing](https://docs.cloud.google.com/eventarc/standard/docs/run/route-trigger-cloud-firestore).
The container argument override is documented by
[`gcloud run deploy`](https://docs.cloud.google.com/sdk/gcloud/reference/run/deploy).
The timer uses the service's base URL as its
[OIDC audience](https://docs.cloud.google.com/run/docs/triggering/using-scheduler).

The new `.github/workflows/booking-worker.yml` is an optional five-minute fallback
and manual runner. It runs only on `main` when repository variable
`BOOKING_PROCESSING_ENABLED` is exactly `true`, with `FIREBASE_PROJECT_ID`,
`GCP_WORKLOAD_IDENTITY_PROVIDER`, and `GCP_LOTTERY_WORKER_SERVICE_ACCOUNT` configured.
It reuses the existing Google OIDC setup and runs only `process-bookings`.
GitHub scheduling can be delayed; use the service/event/timer path for normal operation.

Before enabling submission UI, deploy the compatible PR 1 rules and this worker,
inspect the target project's existing requested/rejected/declined backlog, and
verify acceptance, conflict retry, decline replacement, and rejection release in
the test project. Each sweep scans matching records, including processed declines
and rejections that then no-op; pagination/archival can follow if volume grows.
Original pending loss push events remain historical outbox entries and can still
deliver after a replacement win if the separate delivery runner is behind.

## Branch and review

`feature/booking-worker` starts from PR 1 commit `8ed1194`. PR 1's app, rules,
schema, documentation, and tests remain unchanged. Open PR 2 against
`feature/booking-contracts-backend` while PR 1 is under review so only worker changes
appear. After PR 1 merges, update PR 2 onto the resulting `main` history and retarget
its base; account for squash merging before rebasing. Keep `.idea/misc.xml` out of
this PR. Nothing here commits or pushes the working tree.
