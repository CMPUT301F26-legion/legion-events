# Campsite Lottery Application

The home screen lists active Firestore campsites with text search, guest-capacity,
and nightly-price filters. See [CONTRIBUTING.md](CONTRIBUTING.md) for project conventions
and [SCHEMA.md](SCHEMA.md) for account roles and listing fields.

The [booking-offer guide](doc/booking-offers.md) describes the Part 6 offer repository,
the screens under **Lotteries → Booking offers → View offers**, decline behavior, and tests.

## Generate the API documentation

Run this command from the repository root:

```sh
./gradlew :app:dokkaGeneratePublicationHtml
```

Open `doc/api/index.html` in a browser. On macOS, you can use:

```sh
open doc/api/index.html
```

Dokka reads the `/** ... */` KDoc comments in the Kotlin source. The HTML includes
public, protected, internal, and private declarations from the app's main source,
so controller helpers are documented too. Add simple explanations to every new or
changed controller, view, and model function. Describe inputs with `@param` and
return values with `@return` when useful; describe model fields with `@property`.

Generated HTML is saved in `doc/api/` and ignored by Git. Generate it locally
when needed; commit the KDoc source and Dokka configuration, not the HTML output.
The first generation may download Dokka and its dependencies.

See the [Dokka Gradle guide](https://kotlinlang.org/docs/dokka-gradle.html) for the
generator's available tasks and the [onboarding notes](doc/onboarding.md) for app setup.

## AI use

- **Model:** Claude Opus 5.5 (Anthropic), through Claude Code in VS Code.
- **Dates accessed:** October 30 to November 6, 2026.
- **Prompts:** asked it to structure the user stories, write features and tests, review pull
  requests, check code against the Firestore rules, resolve merge conflicts, and help update
  UML diagrams, CRC cards, and wiki pages.
- **Used for:** code and tests across the app (profile and onboarding, navigation, campsite
  search and details, owner campsite screens, lottery setup and management, lottery draw and
  booking offers, waitlist join and leave, owner notices), `SCHEMA.md`, the UML diagrams in `doc/uml`, and the CRC cards in
  `images/crc_cards`.
- Every team member reviewed and tested the AI-generated code before merging.

## Third-party libraries

Besides Kotlin, Android, Jetpack Compose, AndroidX, and Firebase (Authentication,
Firestore, Cloud Messaging, and the Admin SDK in `lottery-worker/`), the project uses:

| Library | Version | Used for | License |
| --- | --- | --- | --- |
| [ZXing core](https://github.com/zxing/zxing) (`com.google.zxing:core`) | 3.5.3 | Drawing campsite QR codes (S14) | Apache 2.0 |
| [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (`com.journeyapps:zxing-android-embedded`) | 4.3.0 | Scanning campsite QR codes (S05) | Apache 2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (`-play-services`, `-test`) | see `gradle/libs.versions.toml` | Awaiting Firebase tasks; testing controllers | Apache 2.0 |
| [JUnit 4](https://junit.org/junit4/) | 4.13.2 | Unit tests | EPL 1.0 |
| [Mockito](https://site.mockito.org/) | 5.20.0 | Unit tests | MIT |
| [Dokka](https://github.com/Kotlin/dokka) | 2.2.0 | Generating the API documentation | Apache 2.0 |

