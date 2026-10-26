# Initial entrant onboarding (US 01.09.01)

Open the app with a network connection. It shows “Opening your account…” while it
reuses `FirebaseAuth.currentUser` (or signs in anonymously) and initializes
`users/{uid}`. Success opens the welcome screen. Failures show Retry; disabled
profiles show Account disabled and allow checking again.

## Project configuration

The existing `app/google-services.json` matches project `legion-events-f529b` and
application ID `com.example.legion_events`. The app uses the default Firestore
database. Firebase infrastructure is managed separately; Anonymous Authentication
and deployed rules/indexes are supplied by that project. No configuration values,
credentials, rules, or infrastructure are added by this implementation.

`requirements.json`, `SCHEMA.md`, and Firebase setup documentation were absent from
this checkout. The onboarding profile contract comes from the task's infrastructure
context. The CRC cards in `images/crc_cards` guide the implementation:

- `UserProfile` holds identity, personal information, role, disabled state, alert
  preference, photo/token references, and timestamps.
- `FirebaseRepository` owns Firebase identity and Firestore access, using
  `FirestoreProfileCreator` for an atomic create-only fallback. Its creation
  payload always uses role `user`; it provides no role-changing API.
- `OnboardingController` coordinates initialization, Retry, and state using an
  activity-retained ViewModel and lifecycle-aware coroutines.
- `AppNavigation` coordinates lifecycle-aware Navigation Compose transitions.
  `OnboardingScreen` and `HomeScreen` only render state and user actions.

The CRC describes blocking owner actions; onboarding also blocks a disabled profile
from entering Home, as requested in this task.

## Identity and profile safety

The app never signs out during onboarding. Firebase persists its authenticated user
across launches while app data remains intact. Clearing app data/uninstalling can
lose an anonymous identity; no username/password recovery is implemented.

A shared mutex serializes sign-in, including an in-flight Firebase task when a
caller is cancelled. A Firestore transaction reads the UID document and creates it
only if absent. Contending transactions retry and then read the existing profile.
The deployed policy denies reading a missing profile but permits creating it.
When that read is denied, `FirestoreProfileCreator` uses a Firestore REST commit
with `currentDocument.exists=false`, then the repository reads the saved profile
from the server. This precondition rejects writes to existing documents atomically,
so even concurrent fallbacks cannot reset a profile. Both timestamps use server
transforms. The REST request authenticates with the same Firebase user's ID token,
uses the configured Firebase project ID, and remains subject to deployed rules.
See Firebase's [write/precondition contract](https://firebase.google.com/docs/firestore/reference/rest/v1/Write)
and [REST authentication documentation](https://firebase.google.com/docs/firestore/use-rest-api).
Existing documents are never updated, including their role, personal information,
preferences, and timestamps. New documents contain exactly:

| Field | Initial value |
| --- | --- |
| `name`, `email`, `phone` | Empty strings |
| `role` | `user` |
| `disabled` | `false` |
| `photoId`, `fcmToken` | `null` |
| `notificationsEnabled` | `true` |
| `createdAt`, `updatedAt` | Firestore server timestamps |

Profile initialization must complete before Home is available. It requires a
server connection, so cached data alone cannot bypass initialization or a disabled check.
Configuration changes reuse the controller. Navigation removes onboarding with
inclusive `popUpTo` and guards duplicate Home transitions. A restored Home is also
gated by initialization in a newly created process.

## Verification

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Unit tests cover the exact creation contract, loading/failure/success, retry
deduplication, disabled profiles/accounts, cancellation, controller retention, and
the create-only REST payload/precondition and server timestamp transforms.
Compose instrumented tests use fake repositories to check loading, Retry, blocked
state, Home back stack removal, state restoration, and restored-Home gating.

`FirebaseRegistrationIntegrationTest` is skipped by default. To explicitly test
the configured Firebase project, run:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.legion_events.FirebaseRegistrationIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.verifyFirebaseRegistration=true
```

This creates/reuses one separate persistent test identity named
`onboarding-integration-check` on the test device, and its matching cloud profile.
It checks eight concurrent initializations, exact defaults, server timestamps, and
unchanged profile data after retries. It does not modify an existing user's profile
or roles. The test prints the UID and a profile fingerprint to compare across
process restarts, without exposing authentication tokens or personal data.

For an end-to-end check against the configured Firebase project:

1. Run the app on a fresh test device/install. Confirm the welcome screen, an
   anonymous Auth user, and a matching `users/{uid}` document with the fields above.
2. Record the UID and profile timestamps. Force-stop and reopen without clearing
   app data; confirm the same UID, document, and unchanged timestamps/data.
3. Disable networking before launch. Confirm an error with Retry and no welcome
   screen. Restore networking and retry; confirm the original UID and profile.
4. Rotate/recreate the screen during loading and after success. Back from Home
   must leave the app instead of returning to onboarding.
5. In an authorized test environment, mark a test profile disabled and reopen;
   confirm the blocked screen. Use the infrastructure repository's tests to verify
   concurrent initialization and deployed role/ownership enforcement.

The existing BoM selects Firebase Auth 25.0.0, which requires Kotlin 2.4 metadata;
the Kotlin/Compose tooling is aligned to 2.4.0 so the existing BoM can be retained.

## Results from this checkout

- Debug APK build, Android test APK build, and lint completed successfully. Lint
  reports warnings about dependency updates and starter resources; no lint errors.
- All 10 JVM tests and all 6 standard instrumented tests passed, including starter tests.
- A fresh installation on the configured Medium Phone emulator authenticated
  anonymously. Force-stop/reopen and Retry kept the same persisted Firebase UID.
- The initial live failure was isolated to a denied profile read before creation.
  A create-only request with the exact contract succeeded under the existing rules.
- The opt-in integration test passed against the configured project: eight
  concurrent initializations created/reused one identity/profile, with exact
  defaults and server timestamps; subsequent retries left all fields unchanged.
- Repeating that test in two separate processes preserved the same UID and exact
  cloud profile fingerprint, including timestamps.
- The real app reached Home, retained its identity after force-stop/reopen and
  rotation, displayed Retry while offline, and reached Home after reconnecting and
  retrying. Back from Home left the app.

The matching Android Firebase configuration is already present. No production
rules or infrastructure changes are required for the first-launch fix. Disabled
state is verified using fake repositories; no production profile was disabled or
promoted for testing.

Changed implementation files: `MainActivity.kt`, `model/UserProfile.kt`,
`data/UserRepository.kt`, `data/FirebaseRepository.kt`, `data/FirestoreProfileCreator.kt`,
`controller/OnboardingController.kt`, `controller/AppNavigation.kt`,
`view/OnboardingScreen.kt`, and `view/HomeScreen.kt` under the existing application
package; `AndroidManifest.xml`, `res/values/strings.xml`, `app/build.gradle.kts`,
and `gradle/libs.versions.toml`. Tests are in `OnboardingControllerTest.kt`,
`NewProfileContractTest.kt`, `FirestoreProfileCreatorTest.kt`,
`OnboardingNavigationTest.kt`, and `FirebaseRegistrationIntegrationTest.kt`;
this document records setup and verification.
