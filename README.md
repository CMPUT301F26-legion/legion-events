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
- **Prompts:** asked it to structure the user stories, review pull requests, write features
  and tests, check code against the Firestore rules, resolve merge conflicts, and help update
  UML diagrams, CRC cards, and wiki pages.
- **Used for:** the profile, onboarding, navigation, and lottery setup code and tests
  (S15, S16, owner Lotteries tab), the lotteries section of `SCHEMA.md`, the UML diagrams
  in `doc/uml`, and the CRC cards in `images/crc_cards`.
