# Development guide

Start with [Decisions and scope](DECISIONS.md) for the agreed direction. The repository's main README is written for listeners; keep development details in `docs/`.

## Scaffold status

The initial scaffold provides a native Android app shell with a real, initially empty local library. It establishes:

- Compose screens, Navigation 3, lifecycle-aware ViewModels, and Hilt dependency injection.
- Room 3 storage for books, tracks, listening progress, and bookmarks.
- A persistent system/light/dark appearance preference using DataStore.
- One Media3 library service and a phone-side controller connection.
- Android Auto discovery and browsable library declarations.
- A Gradle wrapper, pinned dependencies, and a GitHub Actions verification workflow.

Book import, usable book playback controls, progress restoration, chapter navigation, downloads, artwork loading, catalogue APIs, and issue submission are follow-up features. No reporting backend is provisioned. Android Auto declarations do not constitute device or car acceptance testing.

## Toolchain

The minimum Android version remains **API 26 / Android 8.0**. The current scaffold compiles and targets **API 37**.

| Component | Pin |
| --- | --- |
| Build JDK and Java target | 17 |
| Gradle wrapper | 9.6.0 |
| Android Gradle plugin | 9.4.1 |
| Kotlin / Compose compiler / serialization plugin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| KSP | 2.3.12 |
| Dagger/Hilt | 2.60.1 |
| AndroidX Hilt | 1.4.0 |
| Navigation 3 | 1.2.0 |
| Room 3 | 3.0.3 |
| DataStore | 1.3.0 |
| Media3 | 1.11.1 |

The [version catalogue](../gradle/libs.versions.toml) is the source of truth for dependency versions. Keep the Compose compiler and serialization plugins aligned with Kotlin.

AGP 9 supplies built-in Kotlin support. Do not also apply `org.jetbrains.kotlin.android` or `kapt`. Hilt and Room use KSP. Room 3 uses the `androidx.room3` namespace and `room3` Gradle extension.

Gradle 9.6 is retained within the selected Kotlin toolchain's documented compatibility range. The optional configuration cache is not enabled: the initial local run encountered an internal cache serialization error. Ordinary build caching remains enabled.

## Local setup

1. Install a JDK 17 distribution and an Android Studio version that supports the pinned Android Gradle plugin.
2. Install the Android SDK platform for API 37 and Build Tools 36.0.0 using Android Studio's SDK Manager. The current Android CLI package names are `platforms/android-37.0` and `build-tools/36.0.0`.
3. Accept the SDK licenses through the SDK Manager.
4. Open the repository root in Android Studio. For command-line builds, set `ANDROID_HOME` to the SDK directory or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.
5. Use the checked-in wrapper; a separate Gradle installation is unnecessary.

From the repository root:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

On Windows, use `gradlew.bat` with the same tasks.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on an API 26+ emulator or device from Android Studio, or with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This development build opens an empty library. It does not yet import audiobooks.

## Code structure

One `:app` module is intentional. Keep boundaries clear and extract modules when the implementation justifies it.

| Location | Responsibility |
| --- | --- |
| `app/src/main/java/io/github/drolevar/audiotales/ui/` | Compose navigation, screens, theme, and ViewModels |
| `data/library/` | Stable library models and repository contract |
| `data/local/` | Room entities, DAO, and database |
| `data/preferences/` | DataStore preferences |
| `di/` | Application-scoped persistence dependencies |
| `playback/` | Media library service, item resolution, and controller connection |
| `app/src/main/res/xml/automotive_app_desc.xml` | Android Auto media app declaration |
| `app/schemas/` | Generated Room schema history |
| `docs/` | Decisions, development instructions, and verification status |

The application ID and namespace are `io.github.drolevar.audiotales`. Debug builds use `io.github.drolevar.audiotales.debug`. The app label is **AudioTales**, and the initial version is `0.1.0-dev` (`0.1.0-dev-debug` for debug builds).

## Playback and data boundaries

The service owns ExoPlayer and the MediaLibrarySession. Each Activity owns its MediaController connection and releases it with the UI lifecycle, so stopping one Activity does not disconnect another. Releasing a phone controller must not release the service player during ongoing playback.

Only controllers Android/Media3 identifies as trusted can browse or control the library. This prevents arbitrary apps from reading audiobook titles. Hosts without that trust are rejected; verify supported Android Auto and DHU connections before release. Keep the legacy service binding probe distinct from the later verified controller connection.

Books and tracks have stable IDs. Resolve external media requests against the repository and construct playable items from the stored source; do not accept a caller-supplied URI as authority. Browsing an empty library should succeed with an empty result.

Listening progress records both track identity and position in milliseconds. Preserve this distinction when importing multi-file books. Room schema migrations must preserve progress and bookmarks; do not add destructive migration fallbacks.

Keep file access behind repository/import code. The next import implementation must persist eligible Storage Access Framework URI grants and handle revoked access or moved files. Initial system backup is disabled pending a deliberate restore policy.

## Reporting boundary

The accepted reporting flow is recorded in [decisions 7–9](DECISIONS.md#7-convenient-automatic-reporting--accepted). The scaffold does not expose a nonfunctional Send control or upload diagnostics.

Future implementation must redact on-device, persist an outbox, retry safely, and use a server-side GitHub App. A receipt/status flow must work without granting listeners access to the private repository. Detailed bundles require authenticated maintainer access. Automatic crash uploads remain a separate opt-in.

Retrofit, OkHttp, kotlinx.serialization, Coil, and WorkManager are the agreed tools for the features that need them. Add and wire integrations as those features arrive instead of constructing unused clients or workers.

## Verification

The initial local verification completed successfully on 2026-10-24 with JDK 17.0.21, AGP 9.4.1, and the checked-in Gradle wrapper:

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | Passed; debug APK generated |
| `:app:lintDebug` | Passed; no errors |
| `:app:testDebugUnitTest` | 4 passed; no failures, errors, or skipped tests |
| Room schema generation | Passed; version 1 schema committed in `app/schemas/` |
| APK manifest inspection | Minimum SDK 26, target/compile SDK 37, debug application ID verified |

The focused tests cover persisted-source resolution, rejection of supplied playback locations, rejection of URI-shaped media IDs, and rejection of an entire request containing an unknown track. Lint reports a non-blocking newer-Gradle notice; the chosen version is retained for toolchain compatibility.

No emulator, physical-device, or Android Auto/DHU acceptance tests have run yet.

The GitHub Actions workflow verifies the Gradle wrapper, builds the debug APK, runs Android lint and local unit tests, and retains useful build artifacts. It does not publish to an app store or provision a reporting service.

Two lint exceptions are scoped in the manifest and must remain deliberate: the exported media service is protected by the verified-controller gate, and the voice-search intent requirement is deferred until search-to-playback resolution exists. Remove the latter exception when implementing voice search. There is no global lint disable or lint baseline.

Before calling the player ready for listeners, also perform:

- API 26 and current-platform launch, navigation, theme, and process recreation checks.
- Storage permission loss and file movement recovery checks.
- Playback interruptions, headphone disconnection, service lifetime, and durable resume checks.
- Android Auto/DHU browsing, media controls, and real playback checks.
- Reporting redaction, offline retry, reconciliation, and private-log access checks when reporting exists.

Record what actually ran. A passing build does not replace these device-level checks.

## Release preparation

Release signing is not configured. Keep signing keys and credentials outside the repository. Preserve each release's version, Git commit, and R8 mappings for diagnostics. Distribution channels, release automation, and a public repository transition remain separate decisions.

## Primary references

Version and API choices were checked against official documentation on 2026-10-24:

- [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [Gradle 9.6 release notes](https://docs.gradle.org/9.6.0/release-notes.html)
- [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Compose setup and compiler](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- [Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3)
- [Room 3 releases and schema configuration](https://developer.android.com/jetpack/androidx/releases/room3)
- [Hilt Gradle setup](https://dagger.dev/hilt/gradle-setup.html)
- [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- [Media library service](https://developer.android.com/media/media3/session/serve-content)
- [Android Auto media apps](https://developer.android.com/training/cars/media/auto)
