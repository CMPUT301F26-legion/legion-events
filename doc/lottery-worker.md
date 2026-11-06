# Lottery draw worker

The standalone [Kotlin/JVM worker](../lottery-worker) implements US 02.05.01
with the Firebase Admin SDK. It runs independently of the Android app.

## Draw behavior

The worker checks undrawn lotteries and runs a Firestore transaction for each
open lottery whose `registrationEndAt` is at or before the run's cutoff. The
transaction reads the current lottery and its `joined` entries in `waitlist`
before writing any results.

- The worker samples up to `winnerCount` unique user IDs at random. A lottery
  covering several campsites has one shared draw, and each winner receives an
  offer for its `campsiteIds`.
- Joined entries become `won` or `lost`. The draw preserves withdrawn entries
  and `entrantCount`. It sets `winnersDrawn` even when the waitlist is empty.
- Each winner receives an `offers/{lotteryId}_{userId}` document with
  `lotteryId`, `userId`, `campsiteIds`, `status: "pending"`, `response: null`,
  `expiresAt`, and server timestamps for `createdAt` and `updatedAt`.
  The worker uses `bookingEndAt` for `expiresAt` because the lottery has no
  separate offer deadline. A delayed draw can create an already-expired offer.
- Each entrant gets one win or loss event in `lotteryNotificationOutbox`.

The transaction commits these writes together. A second run sees `winnersDrawn`
and skips the lottery. If a destination document already exists, the transaction
fails without overwriting it. The worker attempts the remaining draws and reports
the failed lottery so the next run can retry it.

The `offers` contract matches the app's booking-offer screens. Use an app build
with that contract when testing the schedule against a Firebase project.
[Booking offer behavior](booking-offers.md)

## Notification handoff

[`sendNotification()`](../lottery-worker/src/main/kotlin/com/example/legion_events/worker/NotificationOutbox.kt)
stages `lotteryNotificationOutbox/{lotteryId}_{userId}` in the draw transaction.
Each event contains `lotteryId`, `toUserId`, `offerId` (null for a loss),
`type` (`lottery_won` or `lottery_lost`), `deliveryStatus: "pending"`, and server
timestamps for `createdAt` and `updatedAt`.

The workflow can publish these events to Inbox and send FCM pushes through a
separate step. Follow the [notification delivery setup](lottery-notification-handoff.md#enable-delivery)
before enabling `LOTTERY_NOTIFICATIONS_ENABLED`.

## Local verification

Use Java 25. Start the Firestore emulator in a demo project and set
`FIRESTORE_EMULATOR_HOST` to its host and port. The tests skip if this variable
is absent. They create synthetic records in isolated demo namespaces.
[Firebase emulator documentation](https://firebase.google.com/docs/emulator-suite/connect_firestore)

From the repository root in PowerShell, with the emulator running on port 8080:

```powershell
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8080'
.\gradlew.bat -p lottery-worker test
$env:FIREBASE_PROJECT_ID = 'demo-legion-draw'
.\gradlew.bat -p lottery-worker run
```

On macOS or Linux, set the same environment variables and use `./gradlew`.

## Set up the GitHub Actions automation

The [Lottery draw workflow](../.github/workflows/lottery-draw.yml) runs on `main`
at minute 17 of every hour (UTC) and supports manual runs from GitHub's Actions
tab. Both triggers process **all** eligible lotteries in the configured project.
A lottery is eligible once its registration deadline has passed, so a lottery
that closes between scheduled runs waits for the next successful run.
[GitHub schedule documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

### 1. Merge the worker and prepare a test project

1. Commit `lottery-worker/`, `.github/workflows/lottery-draw.yml`, and this guide.
   Push the feature branch and merge its pull request into `main`.
2. Have a GitHub repository admin confirm that Actions is enabled and the
   repository's Actions policy allows `actions/checkout`, `actions/setup-java`,
   and `google-github-actions/auth`. Leave `LOTTERY_DRAW_ENABLED` unset or set
   it to `false` while configuring access.
3. Have a Google Cloud project admin select a team-approved Firebase test project
   with a Firestore Native mode database named `(default)`. Use a real cloud
   test project for this step so the GitHub run exercises Google authentication.
   Use `legion-events-f529b` when setting up the live project later.

### 2. Set up Google Cloud access

An admin who can enable APIs, manage service accounts and identity pools, and
grant IAM roles runs the commands in this section. The example puts the service
account and identity pool in the same project as the target Firestore database.
Run the commands in Google Cloud Shell using Bash, in order, and resolve any
errors before proceeding. Create these resources once per target project.
[Google Cloud federation prerequisites and setup](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines)

Get the numeric GitHub repository and owner IDs. A teammate with repository
access can run this in an authenticated GitHub CLI session and share the two IDs.

```bash
gh api repos/CMPUT301F26-legion/legion-events \
  --jq '{repository_id: .id, repository_owner_id: .owner.id}'
```

In Cloud Shell, replace all three placeholders with the test project ID and
the numeric GitHub IDs from the preceding command.

```bash
LOTTERY_PROJECT_ID='REPLACE_WITH_TEST_PROJECT_ID'
LOTTERY_GITHUB_REPO_ID='REPLACE_WITH_REPOSITORY_ID'
LOTTERY_GITHUB_OWNER_ID='REPLACE_WITH_REPOSITORY_OWNER_ID'
LOTTERY_POOL_ID='legion-lottery-github'
LOTTERY_PROVIDER_ID='github-actions'
LOTTERY_SA_ID='lottery-draw-worker'
LOTTERY_SA_EMAIL="${LOTTERY_SA_ID}@${LOTTERY_PROJECT_ID}.iam.gserviceaccount.com"
```

Enable the required APIs and create a dedicated worker service account. Cloud
Datastore User (`roles/datastore.user`) is the Firestore data read/write role
used in this example.
[Firestore server IAM roles](https://docs.cloud.google.com/firestore/native/docs/security/iam)

```bash
gcloud services enable iam.googleapis.com cloudresourcemanager.googleapis.com \
  iamcredentials.googleapis.com sts.googleapis.com firestore.googleapis.com \
  --project="$LOTTERY_PROJECT_ID"

gcloud iam service-accounts create "$LOTTERY_SA_ID" \
  --display-name="Legion lottery draw worker" \
  --project="$LOTTERY_PROJECT_ID"

gcloud projects add-iam-policy-binding "$LOTTERY_PROJECT_ID" \
  --member="serviceAccount:${LOTTERY_SA_EMAIL}" \
  --role="roles/datastore.user" \
  --condition=None
```

Create a Workload Identity Federation pool and an OpenID Connect provider that
accepts tokens from this GitHub repository on `main`. The numeric IDs identify
the repository and its owner. GitHub uses short-lived credentials for the run.
[GitHub OIDC token claims](https://docs.github.com/en/actions/reference/security/oidc)

```bash
gcloud iam workload-identity-pools create "$LOTTERY_POOL_ID" \
  --project="$LOTTERY_PROJECT_ID" \
  --location="global" \
  --display-name="Legion lottery GitHub Actions"

LOTTERY_GITHUB_MAPPING='google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id,attribute.ref=assertion.ref'
LOTTERY_GITHUB_CONDITION="assertion.repository_owner_id == '${LOTTERY_GITHUB_OWNER_ID}' && assertion.repository_id == '${LOTTERY_GITHUB_REPO_ID}' && assertion.ref == 'refs/heads/main'"

gcloud iam workload-identity-pools providers create-oidc "$LOTTERY_PROVIDER_ID" \
  --project="$LOTTERY_PROJECT_ID" \
  --location="global" \
  --workload-identity-pool="$LOTTERY_POOL_ID" \
  --display-name="Legion Events main branch" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="$LOTTERY_GITHUB_MAPPING" \
  --attribute-condition="$LOTTERY_GITHUB_CONDITION"
```

Grant the GitHub identity permission to impersonate the service account. Print
the two values the GitHub admin needs in the next step. The full provider name
includes the numeric Google Cloud project number and `/providers/github-actions`.
[Google GitHub Actions authentication guide](https://github.com/google-github-actions/auth#workload-identity-federation-through-a-service-account)

```bash
LOTTERY_POOL_NAME="$(gcloud iam workload-identity-pools describe "$LOTTERY_POOL_ID" \
  --project="$LOTTERY_PROJECT_ID" --location="global" --format='value(name)')"

gcloud iam service-accounts add-iam-policy-binding "$LOTTERY_SA_EMAIL" \
  --project="$LOTTERY_PROJECT_ID" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/${LOTTERY_POOL_NAME}/attribute.repository_id/${LOTTERY_GITHUB_REPO_ID}"

gcloud iam workload-identity-pools providers describe "$LOTTERY_PROVIDER_ID" \
  --project="$LOTTERY_PROJECT_ID" \
  --location="global" \
  --workload-identity-pool="$LOTTERY_POOL_ID" \
  --format='value(name)'

printf '%s\n' "$LOTTERY_SA_EMAIL"
```

### 3. Add the GitHub repository variables

In the GitHub repository, open **Settings → Secrets and variables → Actions →
Variables → New repository variable**. Add the four values below. The workflow
reads these through GitHub's `vars` context.
[GitHub configuration variables](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-variables)

| Name | Value for the first test |
| --- | --- |
| `FIREBASE_PROJECT_ID` | The test Firebase project ID, the same value as `LOTTERY_PROJECT_ID` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full provider name printed by `gcloud`, in the form `projects/123456789/locations/global/workloadIdentityPools/legion-lottery-github/providers/github-actions` |
| `GCP_LOTTERY_WORKER_SERVICE_ACCOUNT` | The printed service account email, such as `lottery-draw-worker@your-test-project.iam.gserviceaccount.com` |
| `LOTTERY_DRAW_ENABLED` | `false` until the test data is ready |

### 4. Verify the workflow in the test project

In the test project's Firestore console, create `lotteries/automation-smoke`
with the fields below. This is a synthetic fixture for the worker.

| Field | Firestore type | Test value |
| --- | --- | --- |
| `status` | String | `open` |
| `winnersDrawn` | Boolean | `false` |
| `registrationEndAt` | Timestamp | A few minutes in the past |
| `bookingEndAt` | Timestamp | Tomorrow |
| `winnerCount` | Number (integer) | `1` |
| `campsiteIds` | Array of strings | One test campsite ID |
| `entrantCount` | Number (integer) | `2` |

Add two documents to the top-level `waitlist` collection. Give both
`lotteryId: "automation-smoke"` and `status: "joined"`. Give each a different
string `userId`, such as `test-entrant-a` and `test-entrant-b`. Use the real UIDs
of two test accounts if you also want to open the resulting offer in the app.
Optionally add an otherwise identical undrawn lottery with a future registration
deadline to verify that the worker skips it.

1. Confirm the three project and identity variables point to the test setup.
   Set `LOTTERY_DRAW_ENABLED` to `true`. This enables scheduled runs as well as
   manual runs. If a scheduled run processes the fixture first, inspect that run.
2. Open **Actions → Lottery draw → Run workflow**, select `main`, and run it.
   Open the `draw` job and check that the Google authentication step succeeds and
   the worker reports the number of completed draws.
3. Confirm `lotteries/automation-smoke.winnersDrawn` is `true`. Exactly one
   joined entry should be `won` and one should be `lost`. Check for one `pending`
   document in `offers` for the winner, with `expiresAt` equal to `bookingEndAt`,
   and two events in `lotteryNotificationOutbox`, one `lottery_won` and one
   `lottery_lost`. If you added the future lottery, confirm it is still undrawn.
4. Run the workflow again. The fixture's offer and notification IDs and
   timestamps should stay the same. If the fixture was the only due lottery,
   the log should say `Completed 0 lottery draw(s).`

[GitHub manual-run instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)

### 5. Turn on the live schedule

1. Set `LOTTERY_DRAW_ENABLED` back to `false` and let test runs finish before
   switching the project variables.
2. Confirm the live app uses the `offers` repository contract and that the
   waitlist feature writes top-level `waitlist` documents with `lotteryId`,
   `userId`, and `status: "joined"`. The worker marks a due lottery with no joined
   entries as drawn. Complete the [notification setup](lottery-notification-handoff.md#enable-delivery)
   if inbox and push delivery are part of the rollout.
3. Repeat the Google Cloud setup for `legion-events-f529b`, or verify that the
   existing live identity has the same branch restriction and Firestore access.
   Update all three GitHub project and identity variables to their live values.
4. Review the live project's open, undrawn lotteries with past deadlines. The
   first enabled run processes that backlog, including lotteries with empty
   waitlists. Offers from a late draw can already be expired.
5. Set `LOTTERY_DRAW_ENABLED` to `true`. Check the next scheduled run in the
   Actions tab and confirm the expected results in Firestore. A manual run from
   `main` also processes the live backlog.

### Troubleshooting and pausing

| Symptom | What to check |
| --- | --- |
| Workflow or **Run workflow** button is missing | Confirm the workflow is merged into the default branch, Actions is enabled, and the person triggering it has repository write access. |
| `draw` job was skipped | Select `main` and check that the repository variable `LOTTERY_DRAW_ENABLED` is exactly `true`. |
| Google authentication fails | Check the full provider name, the GitHub numeric IDs and branch condition, and the service account's `roles/iam.workloadIdentityUser` binding. New IAM changes can take a few minutes to propagate. |
| Firestore reports `PERMISSION_DENIED` or database missing | Check the selected project ID, the `(default)` database, and the worker service account's Firestore role on that project. |
| Worker reports zero draws | Check that the intended lottery has `status: "open"`, `winnersDrawn: false`, and a Firestore Timestamp for `registrationEndAt` at or before the run. |
| A lottery draw failed | Inspect the lottery ID and underlying error in the job log. The failed lottery's transaction rolls back while other eligible lotteries can still complete. Resolve the cause and rerun. |
| Offers exist but notifications are missing | Check the outbox and the notification consumer. Creating an outbox event is the worker's handoff to inbox and push delivery. |

To pause future draws, set `LOTTERY_DRAW_ENABLED` to `false`. Review any in-progress
run separately. A pause does not undo Firestore transactions that already committed.
