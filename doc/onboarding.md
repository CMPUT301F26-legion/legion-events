# Onboarding

The app uses Firebase Anonymous Authentication. On launch it reuses the current
user or signs in anonymously, then creates `users/{uid}` only if it is missing.
Existing profile data is never reset. Successful initialization opens campsite
search; failures show Retry and disabled accounts show a blocked screen.

## Configuration

- Firebase project: `legion-events-f529b`
- Android application ID: `com.example.legion_events`
- Firestore database: `(default)`
- Android configuration: `app/google-services.json`

## New profile defaults

| Field | Value |
| --- | --- |
| `name`, `email`, `phone` | Empty strings |
| `role` | `user` |
| `disabled` | `false` |
| `photoId`, `fcmToken` | `null` |
| `notificationsEnabled` | `true` |
| `createdAt`, `updatedAt` | Firestore server timestamps |

Account roles are `user` and `admin`. Ownership is recorded on individual listings;
see [SCHEMA.md](../SCHEMA.md).

## Responsibilities

`UserProfile` holds data. `FirebaseRepository` handles authentication and profile
storage. `OnboardingController` coordinates setup and Retry. `OnboardingScreen`
renders state, and `AppNavigation` opens Home after setup succeeds.

Sign-in is serialized, and profile creation uses a transaction or a create-only
request when the initial read is denied. Both paths preserve existing documents.
The controller survives screen recreation, and onboarding is removed from the back
stack after success. Firebase keeps the same identity while app data remains intact.

## Checks

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Check that reopening keeps the UID and profile, failed setup offers Retry, screen
recreation does not repeat setup, and Back from Home does not return to onboarding.
