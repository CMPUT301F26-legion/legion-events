# Local lottery triggering

The active trigger is the Android app. `LocalLotteryDrawController.ENABLED` is
`true`. `MainActivity` starts this controller after account setup while the app
is in the foreground and stops it when the app is hidden or the account changes.

The controller observes lotteries owned by the signed-in user. A saved Firestore
change triggers a check, and a five-second clock check catches deadlines that
pass without another write. This works from any screen in the owner's app.
Returning to the app checks overdue lotteries again. A failed lottery does not
block another lottery, and failures are shown in a snackbar with Retry.

## Apply the updated Firestore rules

The repository's `firestore.rules` contains the rules supplied by the group with
the owner draw additions marked `LOCAL DRAW`. A project admin must review and
publish this file in Firebase Console, Firestore Database, Rules. This uses the
existing database. Google Cloud Run, Eventarc, Scheduler, and a service account
are optional for this local mode.

The previous rules prohibit owner-created offers and Inbox records, so the new
client code needs the additions before it can commit a draw. A permission failure
is reported in the app and retried. The rule change is a separate deployment
from an APK build. Review any group rule edits made after the version supplied
in this chat before publishing the file.

## Once-only results

`FirebaseLocalLotteryDrawRepository` uses the ordinary Android Firebase SDK.
It reads the current lottery and queries joined entries from the server. In a
transaction it rereads the lottery and each entry, checks ownership, rechecks the
deadline and draw flag, and checks the number of unique entrants against
`entrantCount`. If a concurrent join or leave makes the query incomplete, the
attempt fails before writing and the next check queries again.

The same transaction writes all of the following.

- An immutable `lotteryDraws/{lotteryId}` owner draw manifest containing
  `lotteryId`, `ownerId`, `entrantIds`, `winnerIds`, and server `createdAt`.
- Joined waitlist statuses `won` or `lost`, with server `updatedAt`.
- One `offers/{lotteryId}_{userId}` per winner, with the existing `pending`
  offer schema and the lottery's `bookingEndAt` as `expiresAt`.
- One visible `notifications/{lotteryId}_{userId}` per entrant with the
  existing Inbox result schema. Winners link to their own offer.
- A pending `lotteryNotificationOutbox` record for optional future push delivery.
- The lottery's `winnersDrawn: true` and server `updatedAt`.

The manifest lets the rules authorize the full supported 100-entrant draw using
shared rule reads. Result writes are permitted only in the transaction that
creates the manifest and changes the flag. Existing offers and Inbox records
cannot be overwritten by the owner draw path. An existing manifest with an
undrawn lottery is reported as an inconsistency for administrator review.

The owner is trusted to choose the manifest. The app samples unique joined users
at random and checks the stored count. Rules bound the owner's authority to their
closed lottery and the declared counts and results, but cannot prove that a
modified owner app sampled fairly or supplied the real complete entrant list.
Use the trusted cloud worker for server-controlled selection when that assurance
is required. Local mode also requires the owner's app to be running and connected.

## Cloud-only code retained for later

| File or folder | Role in local mode |
| --- | --- |
| [LocalLotteryDrawController.kt](../app/src/main/java/com/example/legion_events/controller/LocalLotteryDrawController.kt) | Active foreground trigger. `ENABLED` selects local operation. |
| [LocalLotteryDrawRepository.kt](../app/src/main/java/com/example/legion_events/data/LocalLotteryDrawRepository.kt) | Active authenticated Android draw transaction. |
| [Main.kt](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/Main.kt) | Cloud-only JVM command-line entry point, unused by the Android app. |
| [LotteryAutomationServer.kt](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/LotteryAutomationServer.kt) | Cloud-only Eventarc and timer HTTP endpoints, unused by local triggering. |
| [LotteryWorker.kt](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/LotteryWorker.kt) | Retained Admin SDK draw implementation for the cloud path. |
| [NotificationDelivery.kt](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/NotificationDelivery.kt) | Retained optional backend push delivery and legacy Inbox recovery. |
| `lottery-worker/Dockerfile`, `.gcloudignore`, `.dockerignore` | Cloud deployment packaging. |
| `.github/workflows/lottery-draw.yml` | Optional cloud worker workflow with separate enable variables. |
| `doc/lottery-deadline-automation.md` | Optional cloud deployment instructions. |

For local-only operation leave GitHub's `LOTTERY_DRAW_ENABLED` and
`LOTTERY_NOTIFICATIONS_ENABLED` unset or `false`. Reservation cancellation has
its own independent backend flag. No Android code imports the Admin SDK worker.
FCM push sending remains optional, while Inbox notices are part of every draw.

To switch to cloud-only triggering later, set `LocalLotteryDrawController.ENABLED`
to `false`, rebuild the app, and deploy the trigger and timer described in
`doc/lottery-deadline-automation.md`. Both implementations check the same draw
flag in a transaction, so a local and a cloud attempt cannot both win a race.
For server-controlled selection, also remove the `LOCAL DRAW` owner permissions
from the deployed rules and restore backend-only offer, result, and Inbox writes.
The Android mode constant selects behavior, while Firestore rules control authority.

## Manual test

1. Publish the reviewed rules in the team's test project and run an app build
   configured for that project.
2. Keep the campsite owner's app open and connected. Create two lotteries and
   join them using another test account. Keep the booking period in the future.
3. Change each `registrationEndAt` Timestamp to a past time in the Firebase
   console. The current owner edit form locks lotteries after registration
   opens or entrants join. Local triggering honors successfully saved changes
   and does not change that edit restriction.
4. Confirm `winnersDrawn` becomes true, the entrant's waitlist entry becomes
   `won`, and one offer and one Inbox result appear for each lottery. Open the
   result on the entrant's device and check the offer's campsite and deadline.
5. Close and reopen the owner's app. Confirm no duplicate results or changed
   result timestamps. Test a future deadline passing with the owner app open.
6. Test with the owner app hidden. On return it should process the overdue
   lottery. Test an unavailable connection or denied write and check the visible
   error and Retry behavior.

## Automated checks

From the repository root, run the Android tests and checks.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Start a Firestore emulator and run the owner rule tests using Node.js and pnpm.
The tests use synthetic demo projects and never deploy rules to the live project.
They include a 100-winner draw, recipient-only reads, premature draws, another
owner, malformed counts, duplicate attempts, and conflicting offers.
The existing-permission regression checks also cover profiles, campsite ownership,
lottery creation and edits, joining and leaving, offer declines, reservations,
Inbox read markers, conversations, messages, photos, ratings, owner notices,
admin moderation, and disabled or unauthenticated clients.

```powershell
pnpm --dir tests/firestore install --frozen-lockfile --ignore-scripts
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8080'
pnpm --dir tests/firestore test
```
