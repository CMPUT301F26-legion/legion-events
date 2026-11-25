# Firestore schema

Project: `legion-events-f529b`. Database: `(default)`.

## Accounts

Profiles are stored at `users/{authUid}`. Account roles are `user` and `admin`.
New profiles use `role: "user"`. Clients do not assign admin privileges.
Listing ownership is identified by `campsites.ownerId`, not by an owner account role.
Lottery entrants are participating users, not another account role.

Profile fields: `name`, `email`, `phone`, `role`, `disabled`, `photoId`,
`notificationsEnabled`, `fcmToken`, `createdAt`, `updatedAt`.

## Campsite search

Search reads `campsites/{id}` where `active == true`.

| Field used by search | Type |
| --- | --- |
| Document ID | String, not an extra stored field |
| `ownerId` | User ID string |
| `name`, `description`, `locationName` | Strings |
| `maxGuests` | Integer |
| `pricePerNight` | Integer CAD cents (`6550` = CAD $65.50) |
| `amenities`, `tags` | String arrays |
| `active` | Boolean |

Other campsite fields remain unchanged: `location`, `rules`, `cancellationPolicy`,
`availableNights`, `photoId`, `ratingAverage`, `ratingCount`, `createdAt`, `updatedAt`.
References use string IDs, coordinates use GeoPoint, instants use Timestamp, and
night dates use `YYYY-MM-DD`. Security rules and indexes are maintained separately.

## Owner campsite writes

S13 writes exactly the fields the deployed security rules allow. A new `campsites/{id}`
has `ownerId` set to the signed-in user, `active: true`, `ratingAverage: 0`,
`ratingCount: 0`, `photoId: null`, and `createdAt`/`updatedAt` server timestamps.
`location` is required. `rules` is one string with one rule per line, as the rules'
`text()` check requires. Updates change only the editable fields and `updatedAt`.

A new cover photo goes to `photos/{id}` with `targetType: "campsite"` and
`mimeType: "image/jpeg"`, in the same batch that sets the campsite's `photoId`. Its
Base64 data is at most 280,000 characters. The photo rule reads the campsite with
`get()`, which cannot see writes in the same batch, so a new campsite is created first
and its photo is added in a second write. Earlier photos are kept in the gallery.

## Campsite details and photos

Details read the selected active `campsites/{id}` document. They display description,
amenities, tags, GPS coordinates, rules, cancellation policy, stored ratings, and
`availableNights`. The reader accepts rule text or a string array. Missing optional
details show a fallback. Listed nights are owner-provided dates; this screen does
not check `bookedNights`, reserve dates, or create lottery entries.

Photos are stored at `photos/{id}` with `ownerId`, `targetType`, `targetId`,
`imageBase64`, `mimeType`, `createdAt`, and `updatedAt`. The gallery reads photos
where `targetType == "campsite"` and `targetId` matches the selected campsite.
Its `photoId` identifies the cover, shown first when present in those results.
Image data is Base64-encoded JPEG, WebP, or PNG, rather than a download URL.

## Lotteries

Lotteries are stored at `lotteries/{id}`. Any user can create one for active campsites
they own. Field names and limits match the deployed security rules, which reject a
document with any missing or extra field.

| Field | Type |
| --- | --- |
| Document ID | String, not an extra stored field |
| `ownerId` | User ID string |
| `campsiteIds` | String array of 1 to 8 active campsite IDs owned by `ownerId` |
| `registrationStartAt`, `registrationEndAt` | Timestamp; start before end, end in the future when created |
| `bookingStartAt`, `bookingEndAt` | Timestamp; booking starts no earlier than `registrationEndAt` |
| `winnerCount` | Integer, 1 to `waitlistLimit` |
| `waitlistLimit` | Integer, 1 to 100; the app saves 100 when the owner sets no limit |
| `entrantCount` | Integer, 0 when created; changed only by joining or leaving the waitlist |
| `status` | `"open"` when created |
| `winnersDrawn` | Boolean, `false` when created; set once when winners are drawn |
| `createdAt`, `updatedAt` | Server timestamps |

The app picks days, not times: registration opens at the start of its first day and
closes at the start of the day after its last day; booking works the same way.
An owner can edit a lottery until registration closes and the draw starts (US 02.04.06).
Once registration opens, its first day cannot change. Once entrants have joined, the
campsites cannot change and `waitlistLimit` cannot go below `entrantCount`. An unchanged
day keeps its saved timestamp, so an edit from another time zone does not move it.

## Waitlist

Entries are stored at `waitlist/{lotteryId}_{userId}`, one per user per lottery.

| Field | Type |
| --- | --- |
| `lotteryId` | Lottery ID string |
| `userId` | Entrant's Firebase Auth UID |
| `status` | `joined`, `withdrawn`, or a draw result (`won`, `lost`) |
| `joinedAt` | Server timestamp of the latest join |
| `updatedAt` | Server timestamp |

Joining (S04) creates the entry, or changes a `withdrawn` entry back to `joined`, and adds 1
to the lottery's `entrantCount` in the same transaction. Leaving (S06) changes `joined` to
`withdrawn` and subtracts 1. Both are allowed only while registration is open, and joining
only while `entrantCount < waitlistLimit`. The deployed rules check all of this; a second
join of the same lottery is rejected. The rules let users read only their own existing
entry, so the app finds it with a `userId` query before the transaction.

## Owner notices

An owner's notice to a lottery waitlist (S18) is stored at `ownerNotices/{id}`.

| Field | Type |
| --- | --- |
| `ownerId` | Sender's Firebase Auth UID; must own the lottery |
| `lotteryId` | Lottery ID string |
| `message` | String, 1 to 1000 characters |
| `recipientIds` | UIDs of entrants whose status was `joined` when it was sent, 1 to 100 |
| `createdAt`, `updatedAt` | Server timestamps |

The owner lists notices by `ownerId`. Entrants read notices whose `recipientIds` contain their
UID, shown in the S07 inbox; the inbox hides them while `notificationsEnabled` is false.
Notices cannot be edited or deleted. Add this rule inside
`match /databases/{database}/documents`:

```text
match /ownerNotices/{id} {
  allow read: if mine(resource.data.ownerId)
    || (activeUser() && request.auth.uid in resource.data.recipientIds) || admin();
  allow create: if activeUser() && request.resource.data.ownerId == request.auth.uid
    && request.resource.data.keys().hasAll(['ownerId','lotteryId','message','recipientIds','createdAt','updatedAt'])
    && request.resource.data.keys().hasOnly(['ownerId','lotteryId','message','recipientIds','createdAt','updatedAt'])
    && lottery(request.resource.data.lotteryId).ownerId == request.auth.uid
    && text(request.resource.data.message, 1000) && request.resource.data.message.size() > 0
    && request.resource.data.recipientIds is list
    && request.resource.data.recipientIds.size() >= 1 && request.resource.data.recipientIds.size() <= 100
    && request.resource.data.createdAt == request.time && updatedNow();
  allow update, delete: if false;
}
```

## Booking offers

Offers are stored at `offers/{id}`. Reservation rules expect the ID
`<lotteryId>_<userId>`. The trusted draw worker creates one offer per winner in
the same operation as the results and `winnersDrawn` flag.

| Field | Type |
| --- | --- |
| Document ID | String, stored as the document key |
| `lotteryId` | Lottery ID string |
| `userId` | Winning entrant's Firebase Auth UID |
| `campsiteIds` | Array of offered campsite IDs used by reservation rules |
| `status` | String, initially `pending` |
| `expiresAt` | Timestamp for the response deadline |
| `response` | Absent or null while unanswered, `decline` after a decline |
| `respondedAt` | Server timestamp set when the user declines |
| `createdAt`, `updatedAt` | Server timestamps |

The app queries offers by the signed-in user's UID. To decline, the user must
own a pending, unanswered offer before its response deadline. The repository
leaves the stored status at `pending` and sets `response` to `decline` and both
`respondedAt` and `updatedAt` to server time. The app displays Declined based on
the response. Retrying an already declined offer leaves its document unchanged.

## Reservations

Bookings are stored at `reservations/{id}`. The entrant list queries `userId` using
the current Firebase Auth UID. Direct detail reads also verify `userId`; clients
cannot choose another entrant's identity. Firestore rules must enforce ownership.

| Field | Type |
| --- | --- |
| `lotteryId`, `offerId`, `campsiteId`, `userId`, `ownerId` | String document IDs |
| `startDate`, `endDate` | Firestore Timestamps for check-in and checkout |
| `guestCount` | Positive integer |
| `totalPrice` | Nonnegative integer CAD cents |
| `rulesAccepted`, `cancellationRequested` | Booleans |
| `status` | String; active stays use `confirmed` |
| `createdAt`, `updatedAt` | Server timestamps |

Active means `status == "confirmed"` and `endDate > now`, including ongoing stays.
Past means `status == "confirmed"` and `endDate <= now`, ordered by latest checkout
and then document ID. Finished stays retain their saved `confirmed` status; no
`completed` status or checkout write is needed. Cancelled, rejected, pending, and
unknown statuses are excluded from both periods.
Checkout is excluded from the occupied nights. A pending cancellation request alone
does not change the saved status. The client can only set `cancellationRequested`
to `true` and `updatedAt` to server time on its own confirmed booking before checkout.
An already pending or completed request is a read-only retry. The worker changes
status to `cancelled`, clears the flag, and sets `updatedAt` to server time, atomically
deleting only `bookedNights` documents whose `reservationId` still matches this booking.
It preserves the offer, original dates, guests, price, and all other reservation fields.
Names and areas are optional reads from `campsites/{id}`;
they do not replace the booking's saved dates, guest count, or total price.
Confirmation CSV export rechecks ownership and requires the saved status to be
`confirmed`. Cancellation CSV requires `cancelled`, a cleared request flag, and
the worker's `updatedAt` timestamp (exported as `cancelledAtUtc`). Cancelled bookings
have their own S10 tab; they are not finished stays in Past.
Receipts are saved to the user-selected document on the device;
no receipt collection or additional Firestore fields are created.

The worker creates `notifications/reservation_cancelled_{reservationId}` in the
same transaction as cancellation. It stores `toUserId`, `fromUserId: null`,
`lotteryId`, `reservationId`, `type: "reservation_cancelled"`, `title`, `body`,
`read: false`, and server `createdAt`/`updatedAt` timestamps. The shared Inbox reads
it through its existing recipient-scoped query and opens that reservation's confirmation.
Repeated worker runs do not rewrite the booking or notification. Entrant clients must
not be allowed to create cancellation notices, set `status`, or release nights; the only
client `status` writes are the owner's acceptance and rejection under [Owner reservations](#owner-reservations).

## Owner reservations

S17 lists bookings with `ownerId == uid` and `campsiteId == <selected campsite>`, which the deployed
reservation read rule (`mine(resource.data.ownerId)`) allows. Two equality filters need no composite
index. Malformed records are left out instead of hiding the rest. Entrant profiles are not read, because
`users/{uid}` is readable only by its owner or an admin. Bookings created by entrants start as
`requested`, so unlike S10, S17's Active tab lists `requested` and `confirmed` bookings with
`endDate > now`, each with its status badge. The calendar fills nights held by `confirmed` bookings and
outlines nights asked for by upcoming `requested` ones. Past lists only `confirmed` stays after
checkout; a `requested` booking whose checkout passes without confirmation is not listed.

Accepting is one transaction that rechecks that the owner owns the booking and that it is `requested`,
before its `endDate`, with no cancellation pending, then:

| Write | Fields |
| --- | --- |
| `reservations/{id}` update | `status: "confirmed"`, `updatedAt` server timestamp; nothing else changes |
| `ownerNotices/{newId}` create | The existing notice shape, with `recipientIds: [userId]` and the booking's `lotteryId` |

`bookedNights` stay worker-only, so accepting does not reserve the nights itself, and the rule cannot
check for clashes because it cannot list another booking's nights (see the known gaps below).

Rejecting (US 02.08.01) is one transaction that rechecks that the owner owns the booking and that it is
`requested` or `confirmed` with `endDate` in the future, then:

| Write | Fields |
| --- | --- |
| `reservations/{id}` update | `status: "rejected"`, `updatedAt` server timestamp; nothing else changes |
| `ownerNotices/{newId}` create | The existing notice shape, with `recipientIds: [userId]` and the booking's `lotteryId` |

The optional reason is only sent in the notice; no extra reservation field is written. The
`ownerNotices` rule allows the accept and reject notices because the owner owns the booking's lottery;
a booking whose `lotteryId` is missing or another owner's (for example, a hand-made test record) is
denied, and S17 reports "You don't have permission to accept/reject this reservation."

The deployed rules are in [`firestore.rules`](firestore.rules). The reservation `allow update` has two
branches:

- **Entrant:** may set `cancellationRequested` to `true` on their own `requested` or `confirmed` booking
  that has no pending request, changing only `cancellationRequested` and `updatedAt`.
- **Owner:** before `endDate`, may change only `status` and `updatedAt`, and only along
  `requested -> confirmed` (with no pending cancellation), `requested -> rejected`, or
  `confirmed -> rejected`.

**Known gaps.** `bookedNights` stay `allow write: if false`, and `ReservationCancellationWorker` only
releases nights for entrant-cancelled `confirmed` bookings. So, until the worker handles them:

- a rejected booking's nights are **not released**;
- an accepted booking's nights are **not reserved**, so two overlapping requests can both be accepted;
- a cancellation requested on a `requested` booking is never processed (the rule allows the request,
  but the worker only handles `confirmed` bookings). S17 does not offer Accept for such a booking.

## Ratings

S10c stores one rating at `ratings/{campsiteId}_{userId}`. Repeat reservations for
the same campsite share that document.

| Field | Type |
| --- | --- |
| `campsiteId`, `userId` | String document IDs; `userId` is the current Firebase Auth UID |
| `score` | Integer from 1 to 5 |
| `createdAt`, `updatedAt` | Firestore server timestamps |

The app receives a reservation ID through navigation, checks that it belongs to
the entrant, is `confirmed`, has reached checkout, and has no pending cancellation,
then loads its active campsite and existing score. Saving rechecks these conditions
in a transaction. A new rating uses exactly the five fields above. An update changes
only `score` and `updatedAt`, preserving `createdAt` and both IDs. The same score is
a read-only retry. No reservation ID or profile information is added to a rating.

The existing security rules enforce the UID, document key, score range, allowed
fields, and server timestamps. They require an active campsite when creating a
rating; they do not independently prove that a completed reservation exists.
Completed-stay access is checked by this app's repository. The client does not
update campsite `ratingAverage` or `ratingCount`; refreshing those protected fields
requires trusted backend aggregation.

## Lottery notifications

Local Android draws also create an immutable `lotteryDraws/{lotteryId}` record
with `lotteryId`, `ownerId`, unique `entrantIds`, unique `winnerIds`, and server
`createdAt`. It is written in the same transaction as the results and is used by
the local owner draw rules. The trusted cloud worker uses the shared
`winnersDrawn` flag without needing this client authorization record.
See [local trigger setup](doc/local-lottery-triggering.md).

The draw transaction publishes `notifications/{outboxEventId}` for each entrant
with a joined waitlist entry, together with its result and any winning offer.
It stores `toUserId` (Firebase Auth UID), `lotteryId`, `type`
(`lottery_won` or `lottery_lost`), `offerId` (string or null), and `createdAt`
(the draw event's Firestore timestamp). The app listens for documents addressed
to the signed-in UID. Inbox results are available when push is off. The app
stores its FCM registration in `users/{uid}.fcmToken`. Inbox publication is
independent of push enable flags. The [deadline service](doc/lottery-deadline-automation.md)
handles saved lottery changes and periodic deadline checks.

`lotteryNotificationOutbox` is the backend delivery queue. See the [notification handoff guide](doc/lottery-notification-handoff.md)
for delivery states, Firestore rules, and FCM setup.

## Admin moderation

S19 is for profiles with `role == "admin"`. The deployed rules let an admin read every
`users/{uid}` and `campsites/{id}`, including removed ones, and never allow deleting either.
"Remove" therefore hides the document:

| Action | Write |
| --- | --- |
| Remove a campsite (US 03.01.01) | `campsites/{id}`: `active: false`, `updatedAt` server timestamp |
| Remove a profile (US 03.02.01) | `users/{uid}`: `disabled: true`, `updatedAt` server timestamp |
| Remove an owner (US 03.07.01) | One batch: the profile write above, plus the campsite write for each of the owner's active campsites |
| Remove an image (US 03.03.01) | Delete `photos/{id}`; the photo rules allow delete for its owner or an admin |

These are the only fields the admin rules allow besides the optional `moderationReason`, which
the app does not write. Search shows only `active == true` campsites, and a disabled profile is
blocked at the next app launch. An owner's S13 edits never write `active`, so a removed listing
stays removed. Admin accounts are not removed from S19; roles are changed only in the console.

The S19 Images tab (US 03.06.01) reads every `photos/{id}`, newest `createdAt` first, and labels
each one with the campsite named by `targetId`. Deleting a cover photo leaves the campsite's
`photoId` pointing at a missing document, because the admin rule cannot change `photoId`; the
gallery and cards already skip missing photos, and the owner can set a new cover in S13.

S20 Admin logs read whole collections, which the rules allow for an admin:
`notifications` for the notification log (US 03.08.01), and `ownerNotices` plus the
`messages` whose `senderId` is their conversation's `ownerId` for the owner-to-entrant
message log (US 03.12.01). Entrant replies are not listed.
