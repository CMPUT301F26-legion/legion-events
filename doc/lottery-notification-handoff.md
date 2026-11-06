# Lottery notification handoff for Person 5

Part 6 saves the lottery outcome and queues a notification event. Part 5 owns the
inbox notification and push delivery. The Firestore document is the handoff
between the two, so the delivery code can run separately from the draw worker.

## What `sendNotification()` does today

The standalone Kotlin worker calls this helper once per unique user with a
`joined` entry in the lottery it is drawing. The worker passes the winning offer
ID for a win and `null` for a loss. An entrant who withdrew receives no event.
[Draw call site](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/LotteryWorker.kt)

```kotlin
internal fun sendNotification(
    transaction: Transaction,
    firestore: Firestore,
    lotteryId: String,
    toUserId: String,
    offerId: String?,
)
```

The helper calls `transaction.create(...)` to stage the event in Firestore. The
same transaction writes the waitlist outcomes, creates the winners' offers, and
sets `winnersDrawn`. A committed event therefore points to a committed result
and, for a win, an existing offer. If the draw rolls back, the event rolls back
with it. A rerun skips the completed lottery. Keep inbox and FCM delivery outside
this transaction because Firestore can rerun the transaction callback.
[Helper source](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/NotificationOutbox.kt) · [Firestore transaction behavior](https://firebase.google.com/docs/firestore/manage-data/transactions)

## Firestore event contract

Collection path `lotteryNotificationOutbox/{lotteryId}_{toUserId}`. The suffix
uses the recipient's Firebase Auth UID. Use the full document ID as the stable
event ID when processing retries. Treat this collection as a backend queue.
The Android inbox should read the records that Part 5 publishes for the recipient.

| Field | Firestore type | Value written by the worker |
| --- | --- | --- |
| `type` | String | `lottery_won` or `lottery_lost` |
| `toUserId` | String | Recipient's Firebase Auth UID |
| `lotteryId` | String | ID of the completed lottery |
| `offerId` | String or null | Document ID in `offers` for a win, `null` for a loss |
| `deliveryStatus` | String | `pending` when the event is created |
| `createdAt` | Timestamp | Firestore server timestamp at creation |
| `updatedAt` | Timestamp | Firestore server timestamp at creation |

For example, `lotteryNotificationOutbox/draw-01_alice` could contain
`type: "lottery_won"`, `lotteryId: "draw-01"`, `toUserId: "alice"`, and
`offerId: "draw-01_alice"`. That offer lives at `offers/draw-01_alice`.
A loss for Bob would use `lotteryNotificationOutbox/draw-01_bob`,
`type: "lottery_lost"`, and `offerId: null`. Use the stored `offerId` field to
open an offer.

## How Part 5 can connect delivery

1. **Read committed events from trusted backend code.** Process pending outbox
   documents. If the team uses a Firestore create trigger, listen on
   `lotteryNotificationOutbox/{eventId}`. Plan to process any pending events
   already present when the consumer is first deployed. Firestore trigger events
   can arrive more than once, so handle repeated delivery of the same event ID.
   [Firestore trigger behavior](https://firebase.google.com/docs/functions/firestore-events#limitations)
2. **Create the recipient's inbox item.** Map the event into Part 5's inbox
   schema with its recipient and win or loss message. Use the outbox event ID as
   the inbox document ID or another uniqueness key. On retry, reuse the existing
   inbox item and preserve user state such as whether it was read. Apply the
   inbox's recipient access rules to the resulting record.
3. **Wire the winner's offer.** Pass the stored `offerId` to
   `bookingOfferRoute(offerId)` when the entrant opens the winning result from
   the app. That route is supplied by `feature/offer-decline-ui`. Show the loss
   as a result without an offer action.
4. **Check push preferences at delivery time.** Load `users/{toUserId}` and
   send through FCM only for an active profile with `notificationsEnabled`
   enabled and a usable `fcmToken`. The Profile screen describes this switch as
   a push preference, so keep the inbox result available when push is off or
   there is no token. [Profile screen](../app/src/main/java/com/example/legion_events/view/ProfileScreen.kt) · [FCM Admin SDK](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
5. **Track delivery independently of the draw.** Agree on the consumer's
   delivery statuses and retry policy. Distinguish inbox creation from push
   outcome so a push failure can be retried without creating a second inbox
   item. A crash after FCM accepts a send but before the consumer records it can
   cause a duplicate push on retry. The outbox guarantees one committed event
   per entrant per draw, while delivery needs its own duplicate handling.

## How to verify the integration

Use a Firestore emulator or a team-approved test project. The draw worker's
emulator test already checks the event types, offer IDs, and result counts.
For a cloud test fixture, follow the automation guide's two-entrant example.
[Worker tests](../lottery-worker/src/test/kotlin/com/example/legion_events/worker/LotteryWorkerTest.kt) · [Test project setup](lottery-worker.md#4-verify-the-workflow-in-the-test-project)

| Test | Expected result |
| --- | --- |
| Draw one winner from two joined entrants | One win event and one loss event. Both entrants see their respective inbox items after the consumer runs. |
| Open the win or loss from the inbox | The win opens the offer identified by `offerId`. The loss displays the result without an offer action. |
| Turn off push or use a profile without a token | The entrant still sees the result in the inbox. The consumer skips FCM. |
| Process the same outbox document twice | The recipient still has one inbox item for that event, with read state preserved. |
| Simulate a push failure | The inbox item remains available and the consumer handles the push retry separately. |
| Run the draw worker again for the same lottery | The worker skips the completed lottery and creates zero additional results. |
