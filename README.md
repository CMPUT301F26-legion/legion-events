# Campsite Lottery Application

The home screen lists active Firestore campsites with text search, guest-capacity,
and nightly-price filters. See [CONTRIBUTING.md](CONTRIBUTING.md) for project conventions
and [SCHEMA.md](SCHEMA.md) for account roles and listing fields.

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

Generated HTML is saved in `doc/api/`, outside the ignored build folders, and is
explicitly allowed by `.gitignore`. Regenerate it after changing the KDoc and
include the updated `doc/api/` files with your source changes when committing.
The first generation may download Dokka and its dependencies.

See the [Dokka Gradle guide](https://kotlinlang.org/docs/dokka-gradle.html) for the
generator's available tasks and the [onboarding notes](doc/onboarding.md) for app setup.
