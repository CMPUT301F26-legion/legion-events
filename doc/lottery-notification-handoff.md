# Lottery notification handoff for Person 5

The lottery worker saves each outcome and queues a notification event. A
separate delivery step publishes it to the entrant's inbox and sends an optional
push. Part 5 can build on this Firestore contract for the inbox.

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

## How delivery works

1. **Publish the inbox item.** The `deliver-notifications` command sweeps
   pending outbox events. It creates `notifications/{eventId}` for active
   recipients and preserves existing records on retries. The inbox listens
   for records addressed to the signed-in UID. A win opens `bookingOfferRoute(offerId)`.
2. **Check push preferences.** The backend attempts FCM for an active profile
   with `notificationsEnabled: true` and a token. The inbox item is available
   when push is off or the user has no token. Android registers the FCM token
   after onboarding and updates it when Firebase rotates it.
3. **Deliver and retry.** A short Firestore lease prevents concurrent workers
   from claiming the same push. `sent` means FCM accepted the request.
   `skipped` covers events that need no push, have an unregistered token, or
   belong to a missing or disabled profile. A failed attempt returns to `pending`.
   A crash between sending and saving the outcome can repeat a push. Android uses
   the event ID as its notification tag so a retry can replace the current entry.
4. **Open the app.** Android checks the recipient UID and system permission
   before displaying the push. Tapping it opens Inbox after account setup.

[Delivery worker](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/NotificationDelivery.kt) · [Android messaging service](../app/src/main/java/com/example/legion_events/data/LotteryMessagingService.kt) · [FCM Admin SDK](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)

## Enable delivery

1. Configure the draw workflow for a team test project. Build the Android app
   against that same Firebase project. [Automation setup](lottery-worker.md#set-up-the-github-actions-automation)
2. Enable the Firebase Cloud Messaging API (`fcm.googleapis.com`) and grant the
   worker service account `cloudmessaging.messages.create`, for example through
   `roles/firebasecloudmessaging.admin`. [FCM IAM permissions](https://docs.cloud.google.com/iam/docs/roles-permissions/firebasecloudmessaging)
3. Add the recipient-only read rule below to the team's Firestore rules. Keep
   the outbox backend-only. In `users/{uid}`, allow an authenticated active owner
   to update `fcmToken` and `updatedAt` for registration while preserving the
   existing profile update paths. Require a nonempty string token and
   `updatedAt == request.time`. The Admin SDK writes through IAM.
   [Firestore server security](https://docs.cloud.google.com/firestore/native/docs/security/iam)
4. Set the GitHub repository variable `LOTTERY_NOTIFICATIONS_ENABLED` to `true`
   when the rules, permission, and test client are ready. The draw workflow must
   also be enabled. Delivery runs on the same schedule and processes pending
   results from previous draws.

Merge the inbox rule inside `match /databases/{database}/documents` and check
that broader rules do not grant inbox writes or outbox access to clients.

```text
match /notifications/{eventId} {
  allow get, list: if request.auth != null
    && resource.data.toUserId == request.auth.uid
    && get(/databases/$(database)/documents/users/$(request.auth.uid)).data.disabled == false;
}
```

## How to verify the integration

The emulator tests exercise inbox creation, push preferences, concurrent
attempts, and retries with a fake sender. For a real push, install a test-project
build on a device with Google Play services. Open Inbox to grant the Android
notification permission and confirm the test profile has a token. Use test
account UIDs in the two-entrant fixture and manually run the workflow.
[Worker tests](../lottery-worker/src/test/kotlin/com/example/legion_events/worker/LotteryWorkerTest.kt) · [Test project setup](lottery-worker.md#4-verify-the-workflow-in-the-test-project)

| Test | Expected result |
| --- | --- |
| Draw one winner from two joined entrants | One win event and one loss event. Both entrants see their respective inbox items after the consumer runs. |
| Open the win or loss from the inbox | The win opens the offer identified by `offerId`. The loss displays the result without an offer action. |
| Turn off push or use a profile without a token | The entrant still sees the result in the inbox. The consumer skips FCM. |
| Process the same outbox document twice | The recipient still has one inbox item for that event, with read state preserved. |
| Simulate a push failure | The inbox item remains available and the consumer handles the push retry separately. |
| Run the draw worker again for the same lottery | The worker skips the completed lottery and creates zero additional results. |
