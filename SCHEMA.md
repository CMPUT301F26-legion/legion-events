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

Lotteries are stored at `lotteries/{id}`. Any user can create one for campsites they own.

| Field | Type |
| --- | --- |
| Document ID | String, not an extra stored field |
| `ownerId` | User ID string |
| `title` | String |
| `campsiteIds` | String array of campsite IDs, at least one |
| `registrationStart`, `registrationEnd` | Timestamp |
| `bookingStart`, `bookingEnd` | Night dates in `YYYY-MM-DD` |
| `numWinners` | Integer, at least 1 |
| `waitlistCapacity` | Integer, or missing for no limit |
| `drawn` | Boolean, set once when winners are drawn |

## Booking offers

Offers are stored at `bookingOffers/{id}`. The draw worker creates one offer per
winning user and lottery, reusing its document ID on retries. Creation belongs to
the same atomic draw operation as the lottery results and `drawn` flag.

| Field | Type |
| --- | --- |
| Document ID | String, not an extra stored field |
| `lotteryId` | Lottery ID string |
| `userId` | Winning entrant's Firebase Auth UID |
| `status` | String: `open`, `used`, `declined`, or `expired`; initially `open` |
| `expiresAt` | Timestamp, or null/missing when no response deadline is set |
| `createdAt`, `updatedAt` | Server timestamps |

The referenced lottery supplies the offered campsite IDs and stay dates. An offer
does not reserve nights. Its response deadline is separate from the lottery's
`bookingStart` and `bookingEnd` stay dates.

The app reads offers only for the signed-in user. Declining must atomically check
ownership, an `open` status, and a response deadline that has not passed, then set
`status` to `declined` and `updatedAt` to the server time. Repeating a decline on an
already declined offer succeeds without changing it. Used or expired offers cannot
be declined. Enforce these restrictions in the repository and Firestore rules;
the rules are maintained separately. Notification records can reference the offer
by its document ID.
