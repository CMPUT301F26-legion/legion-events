# Contributing

## Structure

- `model/`: data classes and domain values.
- `controller/`: screen state and user actions. Controllers use `ViewModel` for lifecycle support.
- `view/`: separate Compose screen files that render state and call action callbacks.
- `data/`: repository interfaces and Firebase access. Keep database calls out of views.
- `AppNavigation.kt`: the shared router. Register new screens here and pass IDs as route arguments.

Read the CRC cards in `images/crc_cards` and [SCHEMA.md](SCHEMA.md) before changing data behavior.

## Accounts and ownership

Account roles are `user` and `admin`. Ordinary users share the same capabilities.
A listing belongs to the user identified by its `ownerId`; ownership is not a
separate account role. Users can view and create listings and modify only their own
listings. Admins have additional permissions. An entrant is a participant in a lottery.

## Changes and documentation

Keep each commit focused on one small feature. Separate unrelated changes and
avoid adding future functionality to the same commit.

Add simple KDoc to model, controller, and view functions, including private helpers.
Use `@param`, `@return`, and `@property` where useful. Generate HTML locally when
needed; `doc/api/` is ignored and should not be committed or edited by hand.

## Checks

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:dokkaGeneratePublicationHtml
```

Open `doc/api/index.html` to read the generated documentation. Use an isolated
emulator for device tests and keep production credentials and infrastructure out of app changes.
