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
An owner can edit a lottery only before registration opens and while nobody has joined.

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
does not change the saved status. S10 and S10b perform no writes to reservations,
offers, or `bookedNights`. Names and areas are optional reads from `campsites/{id}`;
they do not replace the booking's saved dates, guest count, or total price.

## Lottery notifications

The backend publishes `notifications/{outboxEventId}` for each active recipient
of a completed draw. It stores `toUserId` (Firebase Auth UID), `lotteryId`, `type`
(`lottery_won` or `lottery_lost`), `offerId` (string or null), and `createdAt`
(the draw event's Firestore timestamp). The app listens for documents addressed
to the signed-in UID. Inbox results are available when push is off. The app
stores its FCM registration in `users/{uid}.fcmToken`.

`lotteryNotificationOutbox` is the backend delivery queue. See the [notification handoff guide](doc/lottery-notification-handoff.md)
for delivery states, Firestore rules, and FCM setup.
