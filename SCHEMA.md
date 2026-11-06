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

## Lottery notifications

The backend publishes `notifications/{outboxEventId}` for each active recipient
of a completed draw. It stores `toUserId` (Firebase Auth UID), `lotteryId`, `type`
(`lottery_won` or `lottery_lost`), `offerId` (string or null), and `createdAt`
(the draw event's Firestore timestamp). The app listens for documents addressed
to the signed-in UID. Inbox results are available when push is off. The app
stores its FCM registration in `users/{uid}.fcmToken`.

`lotteryNotificationOutbox` is the backend delivery queue. See the [notification handoff guide](doc/lottery-notification-handoff.md)
for delivery states, Firestore rules, and FCM setup.
