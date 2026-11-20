# Automatic lottery deadlines

**Optional cloud mode.** The active Android trigger is currently
`LocalLotteryDrawController.ENABLED = true`. The service, Eventarc trigger, and
Cloud Scheduler timer in this guide are retained for later cloud operation and
are unused by local triggering. Start with [local operation](local-lottery-triggering.md).

The backend processes `lotteries/{id}` changes through Eventarc and checks due
lotteries every minute through Cloud Scheduler. A saved deadline in the past
becomes eligible as soon as the write event reaches the service. A deadline that
passes without another write is picked up by the next successful scheduled
check. Both paths reread Firestore and use the same draw transaction.

The transaction saves `won` and `lost` entries, winning `offers`, visible
`notifications`, pending push events, and `winnersDrawn` together. Optional FCM
delivery can run separately. Repeated events, a timer racing an event, and
deadline extensions are checked against the current lottery inside the transaction.

The previous GitHub workflow remains available for manual recovery and reservation
cancellations. It requires repository enable variables and can be delayed. During
the November 19 investigation its six most recent runs were skipped and its
repository variable list was empty. A locally merged workflow alone does not
start a trusted backend.

## Deploy the deadline service

A Google Cloud project admin performs this once in a team test project, then
repeats the verified setup for `legion-events-f529b`. Billing, Cloud Run source
deployment access, and permission to create service accounts and grant IAM roles
are prerequisites. Run the following commands from PowerShell in the project
root with Java 25 and the Google Cloud CLI installed. Cloud Run's IAM gateway
authenticates requests and the runtime service account supplies Firestore access.
[Source deployment prerequisites](https://docs.cloud.google.com/run/docs/deploying-source-code)

Set the real test project ID and choose a Cloud Run region. Read the database
location before choosing the Eventarc location. A multi-region Firestore database
can have a location such as `nam5`, while the service runs in a region such as
`us-central1`.

```powershell
$lotteryProject = 'REPLACE_WITH_TEST_PROJECT_ID'
$lotteryRegion = 'us-central1'
$lotteryService = 'legion-lottery-automation'
$lotteryRuntime = "lottery-deadlines@$lotteryProject.iam.gserviceaccount.com"
$lotteryCaller = "lottery-triggers@$lotteryProject.iam.gserviceaccount.com"
gcloud auth login
gcloud firestore databases describe --database='(default)' --project=$lotteryProject --format='value(locationId)'
$lotteryDatabaseLocation = 'REPLACE_WITH_LOCATION_PRINTED_ABOVE'
```

Enable APIs and create dedicated runtime and trigger identities. Grant the
runtime Firestore data read/write access. Grant the trigger identity permission
to receive events. Resolve errors before continuing, including any source-build
service account permissions reported by Cloud Run.

```powershell
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com eventarc.googleapis.com pubsub.googleapis.com cloudscheduler.googleapis.com firestore.googleapis.com --project=$lotteryProject
gcloud iam service-accounts create lottery-deadlines --project=$lotteryProject --display-name='Lottery deadline runtime'
gcloud iam service-accounts create lottery-triggers --project=$lotteryProject --display-name='Lottery event and timer caller'
gcloud projects add-iam-policy-binding $lotteryProject --member="serviceAccount:$lotteryRuntime" --role=roles/datastore.user --condition=None
gcloud projects add-iam-policy-binding $lotteryProject --member="serviceAccount:$lotteryCaller" --role=roles/eventarc.eventReceiver --condition=None
```

Build the standalone distribution, verify the upload list includes its `bin` and
`lib` contents, and deploy it with authenticated invocation. The distribution
must be rebuilt before every code deployment. The Dockerfile uses the installed
Java distribution and starts its `serve` command. The service handles draws and
Inbox publication with its runtime account.

```powershell
.\gradlew.bat -p lottery-worker installDist
if ($LASTEXITCODE -ne 0) { throw 'Worker build failed.' }
Push-Location lottery-worker
gcloud meta list-files-for-upload
gcloud run deploy $lotteryService --source=. --project=$lotteryProject --region=$lotteryRegion --service-account=$lotteryRuntime --set-env-vars="FIREBASE_PROJECT_ID=$lotteryProject,LOTTERY_DRAW_ENABLED=true" --no-allow-unauthenticated --concurrency=4 --timeout=120 --memory=512Mi --max-instances=3
Pop-Location
gcloud run services add-iam-policy-binding $lotteryService --project=$lotteryProject --region=$lotteryRegion --member="serviceAccount:$lotteryCaller" --role=roles/run.invoker
$lotteryServiceUrl = gcloud run services describe $lotteryService --project=$lotteryProject --region=$lotteryRegion --format='value(status.url)'
```

Create the Firestore write trigger. The path filter covers changes saved by the
app and console. The endpoint validates the event project and lottery document
path, then reads the current document. Eventarc can redeliver events and the
draw's own write produces another event, which becomes a successful no-op.
[Firestore event routing](https://docs.cloud.google.com/eventarc/standard/docs/run/route-trigger-cloud-firestore)

```powershell
gcloud eventarc triggers create lottery-deadline-writes --project=$lotteryProject --location=$lotteryDatabaseLocation --destination-run-service=$lotteryService --destination-run-region=$lotteryRegion --destination-run-path=/events --event-filters='type=google.cloud.firestore.document.v1.written' --event-filters='database=(default)' --event-filters-path-pattern='document=lotteries/{lotteryId}' --event-data-content-type=application/protobuf --service-account=$lotteryCaller
```

Create the timer for deadlines that pass naturally and recovery of missing or
failed events. Its OIDC audience is the service's base URL. Each call scans all
undrawn lotteries, processes the eligible ones, and reports individual failures
after attempting the others. Inspect failed runs in Cloud Run logs and Scheduler.
[Authenticated Cloud Scheduler requests](https://docs.cloud.google.com/run/docs/triggering/using-scheduler)

```powershell
gcloud scheduler jobs create http lottery-deadline-sweep --project=$lotteryProject --location=$lotteryRegion --schedule='* * * * *' --time-zone=UTC --uri="$lotteryServiceUrl/sweep" --http-method=POST --oidc-service-account-email=$lotteryCaller --oidc-token-audience=$lotteryServiceUrl --attempt-deadline=120s --max-retry-attempts=3
```

## Verify the demo scenario before live activation

1. Build the app for the same test Firebase project and use its normal waitlist
   flow to join two lotteries with another test account. Keep booking deadlines
   in the future so offers remain usable.
2. Confirm both waitlist documents have `lotteryId`, the entrant's Auth UID, and
   `status: joined`. Confirm each lottery has `status: open` and
   `winnersDrawn: false`.
3. In the Firebase console change the Timestamp field `registrationEndAt` to a
   few minutes in the past. Keep the correct field name and Timestamp type.
   The app currently locks owner edits after entrants join, so that restriction
   must be accounted for when demonstrating a console deadline change.
4. Check the event in Cloud Run logs. With one entrant and at least one winner
   spot, verify `winnersDrawn: true`, waitlist status `won`, one
   `offers/{lotteryId}_{userId}`, and one `notifications/{lotteryId}_{userId}`
   pointing to that offer. The entrant's Inbox should update and its offer button
   should load the real owned offer. If the recipient lacks access, verify the
   team's recipient-only Inbox and offer rules.
5. Create a short future deadline and leave it alone. The timer should complete
   the draw after the deadline, normally within the next minute plus processing
   time. Service outages or retries can delay completion.
6. Force a second sweep. IDs and original result timestamps should stay unchanged.

```powershell
gcloud scheduler jobs run lottery-deadline-sweep --project=$lotteryProject --location=$lotteryRegion
gcloud run services logs read $lotteryService --project=$lotteryProject --region=$lotteryRegion --limit=30
```

On the live project, review the entire overdue lottery backlog before creating
the timer or enabling the write trigger. Each sweep processes all eligible
lotteries. The configured runtime project must match the trigger's project.
Record a successful deadline-change run and timer run before relying on this
for another demonstration. Deployment and IAM checks require the group admin.

## Local regression checks

With the local Firestore emulator running, use its host and port and run the
standalone tests. Inspect the report to confirm zero skipped tests. The added
HTTP tests cover edited deadlines, normal deadlines, extensions, repeated events,
event/timer concurrency, failed processing and retries, and atomic Inbox writes.

```powershell
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8080'
.\gradlew.bat -p lottery-worker test
```

For local manual processing, `run` performs one sweep and `run --args=serve`
starts the HTTP service. In emulator mode set `FIREBASE_PROJECT_ID` to a demo ID.
The local HTTP listener is intended for the emulator or an authenticated Cloud
Run deployment. Automatic production operation requires the deployed trigger
and timer described above.
