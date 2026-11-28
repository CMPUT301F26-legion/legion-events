# Winner booking contracts (PR 1)

Person 6 owns US 01.04.01, US 01.04.02, US 02.11.01, and US 01.05.01. This PR
supplies models, a working Firebase repository, permissions, and tests for the
later worker and screens. The submission API is not wired into navigation yet.

## Agreed behavior

- The winning entrant chooses one offered campsite and available consecutive dates.
  The lottery booking window is the time to submit, separate from the camping dates.
- Winning or selecting does not book or hold a site. The entrant reviews campsite,
  dates, guests, price, rules, and cancellation policy and explicitly confirms.
- That acceptance is the required approval. No separate campsite-owner approval follows.
- Only successful worker confirmation consumes the offer. A date-conflict rejection
  allows another reviewed attempt under the same unexpired offer and original deadline.
- Entrants may request cancellation only for confirmed reservations before checkout.
- A confirmed decline must select at most one replacement from remaining non-winning
  entrants, keeping all other winners, bookings, and the original booking deadline.
  The replacement processor is PR 2 work; the existing decline API currently only records the response.

The supplied camper storyboard guides review, success, and unavailable states. Its
fixed camping-date block was explicitly superseded by the user's choice of variable
available dates. The owner storyboard supports monitoring without owner confirmation.
Figma was not edited. Illustrated fixed counts, email delivery, and owner draw screens
are outside this PR's scope.

## Models and API

`model/BookingRequest.kt` contains:

| Contract | Meaning |
| --- | --- |
| `BookingOptions` | Owned offer, referenced lottery, and readable active offered campsites |
| `BookingCampsite` | Current listing and occupied-night keys; available = listed minus occupied |
| `BookingTerms` | Rules and cancellation policy reviewed by the entrant |
| `BookingRequest` | Stable attempt ID, offer, campsite, UTC stay dates, guests, reviewed integer CAD cents total, terms, explicit acceptance |
| `BookedNight` | Worker lock with `campsiteId`, `reservationId`, and `date`; stable ID `{campsiteId}_{YYYY-MM-DD}` |

New stay dates must be exact UTC midnight boundaries and cover 1–366 consecutive
nights, excluding checkout. Every selected night must be owner-listed. Requests
cannot start before the current UTC day. A gap or a lock prevents a continuous stay;
selection never reserves those nights. Existing saved bookings retain their original
instant-based mapping and receipts.

`data/BookingRepository.kt` is separate from the existing reservation history and
cancellation repository. `FirebaseBookingRepository` implements:

- `getBookingOptions(offerId)`: server reads for offer, lottery, current campsite
  terms, and provisional `bookedNights`. Incomplete legacy offers are readable by the
  offer repository but cannot authorize booking. Inactive or unreadable sites are unavailable.
- `acceptOffer(request)`: validates identity, offer binding, booking window,
  campsite membership/owner/activity, guest count, consecutive listed dates,
  total, and unchanged reviewed policies. Uses one transaction for request and claim.
- `getRequestsForOffer(offerId)`: server query filtered by current UID and offer ID;
  recovers pending, rejected, or confirmed attempts without relying on local state.

Both repository reads and transaction callbacks recheck the current account. Reuse
the same randomly generated request ID for network retries of one reviewed attempt.
A saved ID with different details fails. A retry after a successful commit returns
its saved state, including if the original network response was lost. A fresh choice
after rejection requires a new ID and explicit review and acceptance.

## Stored lifecycle

The reservation document keeps its existing 14-field schema. No caller supplies
`userId`, `ownerId`, or `lotteryId`; the repository derives them from authentication
and the referenced offer/lottery. `createdAt` is the server acceptance time.

| Action | Offer | Reservation | Occupied nights |
| --- | --- | --- | --- |
| Draw | `pending`, response null | None | None |
| Explicit entrant confirmation | `pending`, response `accept`, `reservationId`, server `respondedAt` | New `requested` record | None |
| Worker success (PR 2) | `used`, same reservation link | `confirmed` | All stay locks created atomically |
| Worker date conflict (PR 2) | Reopen: `pending`, response null, clear pending link/response time | `rejected`, immutable selection retained | No new locks |
| Explicit decline | `pending`, response `decline`, server response time | None | None |
| Cancellation request | Unchanged | Confirmed + `cancellationRequested: true` | Retained until existing worker completes |

`BookingOffer.STATUS_ACCEPTED` is app-facing only: stored `pending` plus `response:
"accept"`. It requires a valid reservation link and disables decline/another acceptance.
It never means confirmed. The offer screen labels it “Awaiting confirmation.” The
worker alone writes `used`; legacy used offers without links remain readable.

Rules use `getAfter()` to require the request and matching offer claim in one atomic
operation. A standalone request, standalone accept response, mismatched link, overwrite,
second request, direct confirmation, direct offer consumption, client night lock, and
forged confirmation notification are denied. Requests are immutable after creation;
clients can only request confirmed cancellation or perform existing owner rejection.
Rules also check current membership, owner, active listing, whole dates, stay bound,
guest capacity, and integer price. Listing-night membership and lock conflicts are
validated by the trusted worker before any confirmation; rules cannot enumerate a
variable-length stay. See [Firebase atomic-operation validation](https://firebase.google.com/docs/firestore/manage-data/transactions#data_validation_for_atomic_operations).

## Worker handoff (PR 2)

For each requested record linked by its accepted offer, the worker must validate the
saved server `createdAt` against the original offer expiry and lottery submission
window. Processing after the deadline must not discard a timely accepted request.
The worker validates all immutable fields, listed nights, current pricing, ownership,
and capacity, then reads every canonical lock before writing anything.

Success must atomically create every lock, confirm the reservation, consume the offer,
and create `notifications/reservation_confirmed_{reservationId}`. The notice fields
are `toUserId`, `fromUserId: null`, `lotteryId`, `reservationId`,
`type: "reservation_confirmed"`, `title`, `body`, `read: false`, and server
`createdAt`/`updatedAt`. The saved reservation ID is the durable booking reference.
Repeated worker runs must not relock, overwrite another booking, or duplicate notices.
The existing S09 confirmation screen must only open success for a confirmed record;
PR 4 adds pending/rejected recovery and confirmation Inbox handling.

On a date conflict, the worker must reject only that attempt and reopen its pending
offer claim in the same transaction, without changing expiry or consuming the offer.
A request race or unavailable site must never produce a success notification.
Owner-rejected confirmed bookings also need their matching locks released; the
existing cancellation processor only releases entrant-cancelled confirmed bookings.

Declines use stable worker-only `offerReplacements/{declinedOfferId}` markers. A marker
records `lotteryId`, `declinedOfferId`, nullable `replacementUserId`/`replacementOfferId`,
and `processedAt`. Select one eligible remaining entrant, create their normal stable
offer and result notice, and commit the marker atomically. With no eligible entrant,
record a null replacement so reruns do nothing. Preserve previous winners, original
initial-draw manifests, reservations, and `bookingEndAt`; do not rerun the initial draw
or reset `winnersDrawn`. Do not issue expired replacement offers. Clients cannot read
or write replacement markers. Full replacement eligibility and worker tests belong in PR 2.

## Integration and rollout

PR 1 removes owner Accept from model, repository, controller, navigation, and view;
owner rejection remains. Nothing here deploys rules, starts a production processor,
or creates live booking data. Keep submission screens disconnected until the
confirming worker is operational. PR 2 delivers processing and its runner/scheduling;
PR 3 connects selection/review/policy acceptance; PR 4 completes status, Inbox, and
end-to-end recovery. Deploy compatible rules and worker before enabling submission.

The current class relationships are in [the UML diagram](uml/booking_contracts.svg)
([editable source](uml/booking_contracts.dot)).

Local checks:

```sh
./gradlew --offline :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
cd tests/firestore
pnpm install --frozen-lockfile --ignore-scripts
cd ../..
pnpm dlx firebase-tools@14.25.1 emulators:exec --only firestore --project demo-booking-contract 'cd tests/firestore && pnpm test'
```

Tests cover UTC dates, gaps, policies, pricing, ownership, account changes, request
recovery, lost responses, competing submissions, immutable links, worker-only writes,
confirmed-only cancellation, and regressions for initial draws and existing permissions.
The emulator uses synthetic demo-project data. Android instrumentation needs a connected
device; compiling those tests does not count as running them.

Validated for this PR: 346 app JVM tests passed; four pre-existing profile/chat
emulator tests were skipped by the default JVM command. All 62 Firestore rules
tests and all 20 existing worker integration tests passed against local emulators.
Android instrumentation sources compiled; no connected device was available to run them.
