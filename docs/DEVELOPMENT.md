# Development guide

Start with [Decisions and scope](DECISIONS.md) for the agreed direction. The repository's main README is written for listeners; keep development details in `docs/`.

For UI work, follow the [accepted light, dark and reusable Figma references and native-control policy](DECISIONS.md#12-ui-design-and-implementation-references--accepted-2026-10-25). Inspect the relevant screen and reusable components through Figma MCP before implementing them in native Compose. Native Android behavior takes priority over exact visual fidelity; use Google's existing controls where they cover the required behavior, including Media3's Compose playback controls. The [UI architecture proposal](UI-ARCHITECTURE.md) describes the recommended translation and identifies screen-level choices that remain proposed.

## Current implementation

The development build implements the first online-library flow: register a compatible HTTPS base URL, browse/search its catalogue, inspect a book and save it to My books. Browse owns catalogue discovery and search; My books contains saved books. Book-URL entry has been removed from the UI. Browse groups independently paged results from selected libraries, with per-source failures, native source selection, shown-result sorting and real-language filtering. Manage libraries supports adding, renaming, sharing and removal; individual-library browsing does not change the selected set. Hidden source records remain available to saved-book refresh, and re-adding their base URL restores the same identity. Library instances are persisted separately from protocol adapters. The shared partner protocol and LibriVox.org's public API are separate adapters; arbitrary websites are not automatically compatible.

Supported book links enter existing Book details through Android text sharing or direct URI delivery. The parser accepts validated partner share/book URLs, LibriVox.org book pages and compatible `audiotales://` identities, including the legacy triple-slash route. It does not execute website JavaScript or nested intents. Opening a link neither saves nor plays a book. Unknown or removed sources require registration consent for unsaved books; registered, deselected sources remain accessible without changing Browse selection. HTTPS filters are unverified, so browser defaults and domain association affect direct delivery. A redirect explicitly targeting the old `com.audiotales` package cannot select this app; share the original HTTPS URL instead.

Book details is a full secondary screen reached from catalogue rows or the saved-book menu. Inspecting a catalogue book does not save or play it. Unsaved metadata renders before independent audio resolution; a playlist failure retains the book information and exposes Retry audio. Save, Listen and Download require resolved tracks and use the canonical saved identity. Saved details render locally and can refresh optional description/language/artwork without renewing tracks. Full deletion clears the details screen's saved/history state, including after returning from the player; explicitly adding the provider book again starts fresh. The track sheet uses actual provider entries; embedded chapter extraction remains future work.

Displayed book titles omit a redundant leading author only when it matches the separate author field and a spaced dash separates a nonblank title. The same derived title supplies phone labels, sharing, Media3 book/album metadata and title sorting. Raw provider and saved titles remain intact, including for existing saved books; uncertain matches and actual track titles retain their original text.

Room 3 schema versions 1–8 preserve books, ordered tracks, listening progress and bookmarks while adding provider identity, registered libraries, per-track request headers and saved artwork URL/referer metadata, durable library selection/registration flags, nullable saved description/language, provider section ranges/shared audio identity, and nullable per-track reader credits. Keep all exported schemas in version control. Source refresh updates only transport URLs/headers after validating the entire stable track set and saved section boundaries; it preserves IDs, order, reader credits and listening history. Optional LibriVox reader metadata refresh uses one extended response and a complete section-reference match before narrow, atomic credit updates; missing attribution preserves known values and does not require valid audio URLs.

One service-owned Media3 ExoPlayer supplies phone controls, background playback and external controllers. The phone uses Google's Media3 Compose controls. Progress is saved periodically and at playback transitions; opening a book does not autoplay. Explicit Retry refreshes online sources and rebuilds the queue from saved IDs. Completed offline books do not require a provider lookup.

Now playing follows the focused Figma composition with native Media3 transport, current-track seeking, speed, a service-owned sleep timer, and Material sheets for book information, tracks and downloads. Ordinary download operations retain active playback controls. Remove downloads keeps saved identity and history; the separate confirmed Remove book action removes downloaded audio and all saved book data. The service drains progress and blocks new playback of a book throughout full removal, including Activity/controller disconnection. Timers offer elapsed-minute presets and end of the current track; they survive screen navigation but are not restored after service/process death.

Media3 DownloadManager/DownloadService own explicit downloads and a shared persistent cache. Queued, downloading, paused, failed and completed states come from that subsystem. Offline availability requires every required track to be complete with all expected bytes cached. Provider sections can share one downloaded recording, with each section played through native Media3 clipping and progress measured within its range. Removing downloads retains library identity and progress. Streaming does not silently populate the managed download cache.

Material 3 adaptive navigation exposes My books, Browse and Settings. My books has progress-aware rows, All/In progress/Downloaded filters and Recent/Title/Author sorting. A secondary Downloads screen, reachable from saved rows and Settings, shows managed transfers, real track/byte progress, download details, pause/resume/retry and confirmed removal. Theme and device-color preferences persist in DataStore; real artwork is fitted without cropping in catalogue results/details, saved rows, the player and mini-player. Partner artwork requests include the library referer required by the image host. A bounded metadata-only refresh fills missing artwork for existing saved books without changing tracks or listening history; artwork failures do not block playback. Local M4B import is on hold with implementation undecided. Embedded chapters, bookmark UI and reporting remain follow-up work. Signed build automation is described in [Signed Android builds](RELEASING.md). Android Auto declarations are present but car compatibility has not been verified.

### Provider access configuration

LibriVox.org uses its public API without a partner client parameter. The shared partner protocol requires a build-time client query configured through `audiotales.partnerClientQuery` in ignored `local.properties`. Without it, the app reports that access is not configured. Never commit that value or generated BuildConfig output. A value embedded in an APK is extractable and is not a secret-storage mechanism. The private compatibility build has been exercised against the supplied partner sites; independent-client provisioning and public-distribution arrangements remain unresolved. Do not present this as an official partnership or publish the compatibility configuration as a production credential.

## Toolchain

The minimum Android version remains **API 26 / Android 8.0**. The current scaffold compiles and targets **API 37**.

| Component | Pin |
| --- | --- |
| Build JDK | 17 in CI; local verification also passes with 21 |
| Java source/target compatibility | 17 |
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

Compatibility qualification checked on 2026-10-25: [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes) supports API 37, requires Gradle 9.6.0 and JDK 17 or later, and defaults to Build Tools 36.0.0. [Kotlin's compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html) lists KGP 2.4.21 with Gradle through 9.7.0 but AGP only through 9.3.1 in its fully supported range. This project uses AGP's built-in Kotlin with the compiler override above; its local and CI checks pass, but do not describe the entire combination as covered by that KGP table. Retain the tested pins and recheck compatibility before a coordinated toolchain change.

## Local setup

1. Use an installed JDK 17 or 21 distribution and an Android Studio version that supports the pinned Android Gradle plugin. Set the IDE's Gradle JDK independently of the application's Java 17 source/target settings.
2. Install the Android SDK platform for API 37 and Build Tools 36.0.0 using Android Studio's SDK Manager. The current Android CLI package names are `platforms/android-37.0` and `build-tools/36.0.0`.
3. Accept the SDK licenses through the SDK Manager.
4. Open the repository root in Android Studio. For command-line builds, set `ANDROID_HOME` to the SDK directory or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.
5. Use the checked-in wrapper; a separate Gradle installation is unnecessary.

From the repository root:

```sh
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Check `./gradlew --version` first: the launcher and daemon must use the intended build JDK. A non-interactive agent shell may not load shell or directory environment hooks. In that case, set `JAVA_HOME` to an installed JDK 17 or 21 for the command; keep machine-specific paths out of tracked Gradle properties. The Kotlin version printed by `--version` is Gradle's embedded Kotlin, not the application's compiler pin.

On Windows, use `gradlew.bat` with the same tasks.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on an API 26+ emulator or device from Android Studio, or with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A fresh installation opens an empty saved collection. Register a compatible library in Browse, select a book and save it; the private compatibility build has the verified online/offline flow described below.

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

No emulator, physical-device, or Android Auto/DHU acceptance tests had run at the initial verification. The later UI emulator checks are recorded below; physical-device and Android Auto validation remain outstanding.

### Bootstrap revalidation — 2026-10-25

The same three-task command above passed locally with OpenJDK **21.0.12.1** in **1m 55s**: **60 actionable tasks, 34 executed and 26 from cache**. `testDebugUnitTest` executed and reported **4 tests, 0 failures, 0 errors, 0 skipped**. Lint reported **0 errors and 1 warning** (`AndroidGradlePluginVersion`, suggesting a newer Gradle); no suppression was added. The debug APK was produced. Sample compiled application classes retain class-file major version 61 (Java 17).

Both the wrapper JAR digest and the pinned distribution checksum matched Gradle's published 9.6.0 checksums. The existing [GitHub Actions run for source commit 3c81638](https://github.com/drolevar/audiotales/actions/runs/40119569487) also passed, including SDK installation, wrapper validation, build, lint, and tests, using JDK 17 on 2026-10-24. That run was inspected, not retriggered.

The local build reported that two dependency native libraries could not be stripped and were packaged unchanged (`libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`); this was non-fatal. No release/R8 build, emulator, physical-device, media playback, or Android Auto validation was performed during bootstrap. The four resolver tests do not exercise Room, Compose, the Android lifecycle, or Media3 controller connections.

### Native UI foundation verification — 2026-10-25

The same assemble/lint/unit command passed with the existing JDK 21 environment in **55s**: **60 actionable tasks, 32 executed and 28 up-to-date**. The four existing resolver tests passed with no failures, errors, or skipped tests. Lint reported **0 errors and the same 1 `AndroidGradlePluginVersion` warning**. No dependency or SDK pin changed. An independent source review found no substantive issues.

The debug APK was installed on an **API 35 Google APIs x86_64 emulator**, at 1080 × 1920 pixels and density 420. Local Maestro flows and screenshot inspection established:

- The real empty library opens; Settings and About are reachable; Android Back returns through Settings to My books.
- Changing to Dark and turning off device colors updates the appearance. Both preferences remain selected after force-stopping and relaunching the app, confirmed from the native accessibility hierarchy.
- Settings and About remain readable and scrollable in portrait and short landscape at system font scale **2.0**. Navigation remains usable after rotation.
- Debug Compose previews render populated rows with long titles and missing authors, loading, and error states at system font scale **2.0**. These fixtures do not populate the real library, and the error preview does not test repository failure/retry behavior.

The default device-color light appearance and static purple light/dark appearances were inspected. An initial Maestro checked-state selector failed to match the switch's ancestor; direct hierarchy inspection and the corrected restart flow verified the saved state. This was a test-selector failure, not an observed preference failure.

These checks do not establish API 26/current-platform coverage, mixed-script or RTL layouts, TalkBack traversal, saved navigation after process death, database failure recovery, playback/controller lifecycle, or Android Auto support. No instrumented Compose test suite was added in this slice. Follow-up platform coverage is tracked separately from feature implementation.

### Online-library flow verification — 2026-10-25

The earlier fixed-host HTML prototype has been replaced by configurable libraries and protocol-based integration. Bounded live API checks verified catalogue pagination, search, book metadata and playlist resolution on the supplied shared-protocol hosts, and the separate LibriVox.org contract. Provider failures remain visible: one catalogue item returned a missing playlist and was not partially saved.

API 35 emulator checks established adding both shared-protocol library URLs, catalogue browsing, Unicode search, saving audioknigi.com.ua book 86717, streaming through the shared service, native seeking, background playback and saved-position restoration after force-stop. Its complete returned track (56,139,276 bytes) downloaded; after a cold app restart with no active network, playback resumed at 3:17 and sought beyond eight minutes. This verifies the provider's returned track set, not the completeness of the publisher's audiobook. The emulator was headless; audible playback on a physical phone was not tested. On the final APK, a further network-disabled app restart resumed and sought past 11 minutes. The supplied librivox.club book also saved, streamed, downloaded, and survived download removal/re-download with its position intact. A controlled invalid-URL fixture produced a playback error; explicit Retry refreshed the source and resumed from 45 seconds. This was fault injection, not an observed naturally expiring URL.

Bounded build/lint/unit/test-APK verification passed in 57 seconds (96 tasks: 24 executed, 72 up to date). After correcting LibriVox catalogue rows with empty page URLs, the final assemble/lint/unit run passed in one minute (60 tasks: 20 executed, 40 up to date). Both used one Gradle worker and a 1536 MiB heap with the emulator stopped. All 21 JVM tests passed (8 parser, 4 network, 6 resolver, 3 download-summary); lint reported zero errors and the existing Gradle-version warning plus an autoboxing hint. All four Android storage tests passed on API 35: v1 and v2 migrations, duplicate-save preservation, and atomic source refresh with changed-track-set rejection. The v2 test also verifies historical library identity repair, headers and deduplication. LibriVox.org book 11455 saved all 29 sections and streamed; after switching sections and seeking, a force-stop/restart restored the second section and position. Its download showed completed/queued/running counts, preserved 21 paused tracks across restart, resumed downloading, and removed all managed tracks without removing the book or progress. The whole 29-track book was not downloaded for offline acceptance. Broader API 26/current-platform, Android Auto, Bluetooth/headset, audio-focus and physical-device checks remain outstanding. Do not infer them from these emulator results.

### Saved collection and artwork verification — 2026-10-25

My books now contains only saved books, with direct book-URL entry removed. Browse retains catalogue browsing/search and library-base-URL registration; the approved My books/Browse/Settings navigation and icons are unchanged. Shared fitted artwork is used in catalogue rows/details, saved rows, player and mini-player. Partner image requests require the library referer; live checks reproduced HTTP 403 without it and HTTP 200 with it on both supplied shared-protocol examples. LibriVox.org uses validated cover art with a thumbnail fallback.

Room version 4 adds nullable artwork URL/referer columns. Metadata-only backfill runs outside migrations, does not fetch playlists, and processes at most 20 missing covers per foreground pass. A mutex-protected process-local cursor rotates attempts so coverless records do not block later books during the session. Cancellation propagates and an interrupted item remains eligible; failures leave playback available. URLs/headers are persisted, while image bytes use Coil caching rather than a guaranteed permanent offline-artwork store.

The final one-worker, 1536 MiB Gradle run passed assemble, lint, all **29 JVM tests**, and test-APK assembly in **1m 10s** (96 tasks: 26 executed, 70 up to date). Lint has zero errors, the existing Gradle-version warning and autoboxing hint. All **five Android storage tests** passed on API 35, including populated schema-3-to-4 migration and artwork-only updates preserving tracks, progress and bookmarks. Independent review caught and verified the bounded-refresh rotation fix.

A populated version-3 app backup was restored into the new API 35 build. Actual upgrade/backfill produced three real covers while preserving all three books, 31 track identities/order, listening progress and bookmarks exactly; foreign-key validation returned no violations. Screenshots verified uncropped artwork in My books, live Browse results, catalogue details, player and mini-player, with the navigation unchanged. Previously downloaded audio remained available: after force-stop and restart with no active network, the service resumed the supplied audioknigi.com.ua recording beyond 12 minutes, and native seek/pause controls worked. These are headless emulator checks, not audible physical-phone or Android Auto acceptance. Remaining Figma features and broader platform coverage remain separate work.

### Collection and Downloads parity verification — 2026-10-25

The My books and Downloads implementations were checked against Figma nodes `34:1126`, `57:1084`, `58:1379` and `58:1433`, while preserving the approved My books/Browse/Settings navigation. Native Material filters, menus, dialogs, progress indicators and Material Symbols provide the controls. Google documents the vector-symbol approach in its [Compose icon guidance](https://developer.android.com/develop/ui/compose/graphics/images/material); imported symbol attribution and license are retained in `NOTICE` and `licenses/`.

The one-worker build, lint, JVM tests and test-APK assembly passed in **1m 44s** (96 tasks: 42 executed, 54 up to date). All **45 JVM tests** passed, including nine collection and seven download-overview cases. All **six Android storage tests** passed on API 35; the added Room relation test observed progress-only, track-only and artwork-only updates while retaining ordering and related state. Independent source review found no actionable defects. No schema migration was needed for the relational collection query.

API 35 interaction and screenshot checks covered All/In progress/Downloaded filters, title sorting, current progress in saved rows, both download entry points, actual per-track download details, completed and paused download rows, empty Downloads, and the no-matching-books filter state. A downloaded book started from Downloads with no active network and the service reported playback beyond 12 minutes. The 29-track example paused/resumed through the collection and Downloads controls. Removing all managed downloads left all three saved books, 31 tracks and listening positions intact. The short 1.8 MB book completed before an initial pause assertion; the pause/resume check was repeated successfully with the longer book. Restored downloads were used to inspect portrait layouts at font scale 1.5 and landscape navigation, without overlapping controls. Build and emulator work ran sequentially to limit memory use.

These checks complete this collection/download slice, not the full Figma objective. Browse/library management, full book details, player layout/sleep timer, final cross-screen visual review and broader platform qualification remain outstanding. No physical-phone, TalkBack, Android Auto or release-build acceptance is inferred from these checks.

### Browse and library management verification — 2026-10-25

Figma nodes `57:955`, `34:1324` and `58:1330` were inspected through MCP for the compact search header, grouped catalogue rows, source-selection sheet and Manage libraries screen. Native Material controls and the approved bottom navigation remain in place. Library order, shown-result sorting and metadata-based language choices follow [decision 17](DECISIONS.md#17-browse-and-library-management--implementation-choices-2026-10-25), rather than inventing unsupported provider parameters.

The final one-worker build, lint, JVM tests and test-APK assembly passed in **1m 26s** (96 tasks: 25 executed, 71 up to date). All **58 JVM tests** passed, including eight Browse state/lifecycle cases and merged title/author search pagination. Lint has zero errors, nine warnings (the existing version/plural suggestions plus three unused strings) and one existing primitive-state hint. All **nine Android storage tests** passed on API 35, including populated schema-4-to-5 migration, source selection/rename/removal/re-add and hidden-source transport renewal. Independent review found and verified the fix for unnecessary requests from hidden Browse destinations; stopping a destination now cancels unfinished work, and returning resumes those pages without discarding completed sources.

A real populated schema-4 backup was upgraded. After removing a source and restarting, all three saved books, 31 tracks, saved positions and bookmark rows were value-identical to the pre-upgrade snapshot. With no active network, the removed source's downloaded book resumed through the service beyond 13 minutes. These are headless emulator state checks, not audible physical-device acceptance.

API 35 interaction checks covered grouped results and covers, the selection sheet, separate checkbox versus catalogue actions, empty selection after restart, renaming, the Android Sharesheet with the source base URL, confirmed removal, re-add, live LibriVox author search, real-language filtering and title sorting. Live pagination expanded a catalogue from 20 to 40 books. After backgrounding and killing the app process, relaunch restored the single-library route, submitted Baker query, English filter and title sort; Up returned to Manage libraries with the persisted selected count. Screenshots and interactions were checked at font scale 1.5 in portrait and landscape, including the rename dialog with the keyboard visible.

The device MCP connection retained an old emulator session, so the installed local Maestro runner was used. Initial automation attempts used an outdated sheet label, an incorrect Add label, and erased a name from a middle cursor position; corrected selectors/input handling verified the actual controls. A player pause assertion was below the visible fold; service state independently established offline playback, and the system media pause command stopped it. No application change was made to hide those test-driver mismatches. Full player layout work remains separate.

Builds and emulator work ran sequentially. These checks complete Browse/library management, not full Figma parity; full book details, remaining player tools, the final cross-screen audit, TalkBack and broader platform qualification remain outstanding.

The GitHub Actions workflow verifies the Gradle wrapper, builds the debug APK, runs Android lint and local unit tests, and retains useful build artifacts. It does not publish to an app store or provision a reporting service.

Two lint exceptions are scoped in the manifest and must remain deliberate: the exported media service is protected by the verified-controller gate, and the voice-search intent requirement is deferred until search-to-playback resolution exists. Remove the latter exception when implementing voice search. There is no global lint disable or lint baseline.

Before calling the player ready for listeners, also perform:

- Broader API 26/current-platform error recovery and accessibility workflows; the bounded non-player checks below do not qualify playback.
- Storage permission loss and file movement recovery checks.
- Playback interruptions, headphone disconnection, service lifetime, and durable resume checks.
- Android Auto/DHU browsing, media controls, and real playback checks.
- Reporting redaction, offline retry, reconciliation, and private-log access checks when reporting exists.

Record what actually ran. A passing build does not replace these device-level checks.

See [Implementation roadmap](ROADMAP.md) for the assessment, remaining risks, milestone acceptance criteria, and tests to add alongside each feature.

### Book details verification — 2026-10-25

The final bounded JDK 21 build passed `assembleDebug`, `lintDebug`, `testDebugUnitTest` and `assembleDebugAndroidTest` in **1m 38s**. All **79 JVM tests** passed without failures, errors or skips. Lint reported **0 errors, 9 warnings and 1 hint**, retaining the previous warning categories. All **11 Android storage tests** passed on API 35 in **11.181s**, including the populated version 5→6 upgrade and metadata-only updates. Schemas 1–5 remain unchanged; schema 6 adds only nullable book description/language fields.

Independent review found no remaining actionable issues. New tests cover inspection without saving, canonical provider/book and track-reference matching, duplicate saves, failed save/playback/download recovery, cancellation of late responses, download initialization recovery, and retry loading state. Book and source transport authority remain in the repository and shared service; this change introduces no player instance.

API 35 runtime checks used the existing three-book, 31-track collection and real provider responses:

- Upgrading and fetching metadata preserved every prior book field, track, progress record, bookmark and library setting; only the new metadata fields were added. All three supplied provider examples displayed fitted covers and real descriptions.
- Catalogue inspection of the completed LibriVox **Walden** recording displayed 23 tracks while the saved collection remained at three books. Explicit Save then added one canonical book. Selecting its first track opened the shared player; the media session reported actual playback at more than 18 seconds.
- Details supported Android sharing, opening Downloads, download start/pause/resume, accurate paused-track labels, and confirmed removal while retaining the saved book. Cancelling removal preserved the offline book.
- After Android reported the background Activity as stopped with saved state, killing process 6394 and returning in process 7105 restored the details destination, book, language and 29-track list. Opening and restoring details did not start playback.
- A cold start with no active default network retained the saved description and offline status. Explicit metadata refresh failed without losing that content; Resume played the downloaded audioknigi example at more than 14 minutes through the shared media session.
- The details screen and track sheet remained usable at font scale 1.5 in portrait and dark landscape. Native scrolling retained access to descriptions and controls in the short window. Covers remained fitted and uncropped.

Validation corrected two selector/setup mistakes: identical search-field/result titles needed a specific result selector, and a process killed while the Activity was still paused had no saved Activity state yet. The stopped-Activity restoration check passed. LibriVox also returned an unfinished Walden project with no source page/audio URLs; inspection correctly showed an unavailable-book state, while the completed recording succeeded. A missing theme argument in a preview was fixed before the passing build. These observations do not establish broader provider availability, Android Auto behavior, or API 26/current-platform qualification. Player arrangement/tools and the full six-screen design audit remain separate work.

### Constrained-window UI corrections — 2026-10-25

A follow-up non-player audit reproduced three problems on API 35 at **600 × 320 dp, font scale 2.0**: fixed collection controls left almost no room for books; removal headings/actions displaced their explanations; and extra IME padding collapsed the library input dialogs when the landscape keyboard opened. Collection controls now scroll with the books. Native removal dialogs use concise headings and scrollable identity/explanation content. Add and Rename keep Android's default keyboard positioning without extra outer IME padding.

The final JDK 21 `assembleDebug`, `lintDebug` and `testDebugUnitTest` run passed in **1m 28s**, with **79 passing JVM tests**, **0 lint errors, 9 warnings and 1 hint**. Independent review found no actionable defects. API 35 runtime checks verified:

- Scrolling reached all four saved-book action targets in the short window, including in dark mode. The normal portrait collection retained its fitted artwork, filters, sort menu and approved navigation. Downloaded filtering returned two books; Author sorting changed their order.
- Source identity and the preservation explanation remained reachable in the library-removal dialog. My books, Downloads and Book details each exposed scrollable download-removal text with working Cancel; dark-mode details retained readable text and actions.
- The landscape keyboard left the Add/Rename fields visible. Editing a library name and submitting with the keyboard's Done action succeeded; the original name was restored. An invalid library address produced a scrollable validation message.
- After cancellation and renaming back, all four book rows, 54 tracks, four progress rows, the empty bookmark table and three source records matched the input fixture exactly. All 12 downloaded audio files, totalling 57,972,201 bytes, retained identical hashes.

The audit also inspected the existing 320 × 600 dp large-text surfaces. API 26/current-platform qualification, TalkBack, the complete empty/error-state matrix and the full six-screen audit are still outstanding. Player layout remains on hold at the user's request; the sleep timer remains unfinished. No player code changed in this correction.

### Non-player platform and accessibility checks — 2026-10-25

Downloads now includes the book title in its primary action and busy-state accessibility descriptions. Its empty-state guidance matches the existing My books destination. The independent review found no actionable issues. No player layout, playback, database or navigation behavior changed.

The debug build, Android lint and all 79 JVM tests passed using JDK 21, one Gradle worker and a 1536 MiB heap. Lint reported zero errors, nine existing warnings and one hint. Builds and emulators ran sequentially.

Two Google APIs x86_64 images were exercised: API 26 revision 16 and API 37.2 revision 6. The latter reported `sdk_full=37.2` and a 16384-byte memory page size. Both opened the empty My books, Browse, Manage libraries and Downloads screens, returned from Downloads to My books, retained Dark mode after a forced restart, and restored the About destination after the Activity was stopped with saved state and its process was killed. About remained scrollable through the license and copyright text with 200% font scaling and a requested 600 × 320 dp landscape window. Wallpaper colors were absent on API 26; on API 37.2 their disabled preference survived restart, and Use device setting followed system light/dark changes.

The four-book fixture opened on both versions with real covers and two completed downloads. API 26 also opened Manage libraries and Walden details. The Downloads accessibility hierarchy identified both books' Play actions on both versions. On API 37.2, TalkBack linear navigation and its displayed speech output confirmed the book rows, both title-specific Play buttons, and download options; TalkBack activation opened and closed the download-details dialog, returning focus to the originating options button. The check used native emulator touch events because ordinary injected swipe commands did not advance TalkBack focus. See [Android's accessibility testing guidance](https://developer.android.com/guide/topics/ui/accessibility/testing).

After each platform run, database comparisons preserved all four books, 54 tracks, four progress records, zero bookmarks and three sources. All 12 downloaded-audio files retained their hashes and total 57,972,201 bytes. Both task emulators were stopped after testing.

These checks do not establish the full error-state matrix, TalkBack coverage for other screens or transfer states, physical-device behavior, audio output, Android Auto, or full Figma parity. At this stage, the API 26 keyboard check was obscured by Gboard's first-run tip and was not counted as verified. The remaining input-dialog and constrained-window matrix stays open. Player layout remains on hold at the user's request, and the sleep timer remains unfinished.

### Browse system-bar correction — 2026-10-25

On API 26 in landscape, Browse's custom search header placed its overflow action behind the system navigation buttons; the library chooser also clipped its Done action. The header now uses `TopAppBarDefaults.windowInsets`, and the chooser passes `WindowInsets.safeDrawing` through the native sheet's `contentWindowInsets` parameter. This follows [Material's inset handling](https://developer.android.com/develop/ui/compose/system/material-insets) while retaining the existing search, navigation and player layouts.

Independent review found no actionable issues. The JDK 21 build, lint and all 79 JVM tests passed in **1m 13s**, with zero lint errors, nine existing warnings and one hint. API 26 and API 37.2 emulator checks at 200% text verified landscape overflow activation, single-library Back/search headers, and the scrollable chooser with its reachable Done action. Portrait checks included opening the chooser from the active keyboard on API 26 and normal-size catalogue results on API 37.2. These are bounded UI checks, not full accessibility or platform acceptance.

After dismissing Gboard's first-run tip on API 26, the short landscape rename field remained visible above the keyboard, and the keyboard's Done action saved a changed name. The original name was restored. Invalid library input showed a scrollable validation message. The input driver initially left a suffix when moving the cursor; clearing both sides of the cursor corrected the test, and the final database comparison confirmed that the original source records were preserved.

On API 37.2, selecting a previously unselected library while offline produced that source's connection error and Try again action while retaining the already loaded source's 20 results. Restoring connectivity and using Try again loaded 20 results for the failed source; the earlier source's results remained visible. This verifies one real partial-outage path, not the complete error matrix.

Both platform runs preserved the fixture's four books, 54 tracks, four progress records, zero bookmarks, three source records and hashes of all 12 downloaded-audio files. The original selection was restored before comparison. Both task emulators were stopped after testing; no build ran alongside an emulator. Broader platform and accessibility coverage remains open, and player layout remains on hold.

### My books search action and collection accessibility — 2026-10-25

The My books app bar now follows Figma node `34:1126` with a native search icon labelled “Browse books.” It selects the existing Browse tab and replaces the scaffold's Settings shortcut. The approved navigation bar and player layout are unchanged. Independent review found no actionable issues; `assembleDebug`, `lintDebug` and all 79 JVM tests passed in **1m 5s**, with zero lint errors, nine existing warnings and one hint.

On API 37.2, switching through this action retained the submitted Browse query and the saved collection's Downloaded filter and Title sort. TalkBack's displayed speech identified “Browse books” as a button, and native double-tap opened Browse with that query intact. Native TalkBack gestures also selected the Light radio option, announced and toggled the device-color switch, and opened About and returned with Back. These checks used actual accessibility focus and touchscreen gestures; no audible-output claim is made.

API 26 also opened Browse through the new action and retained the collection's Downloaded filter and Title sort on return. With Use device setting selected, changing Android's night configuration switched the app between dark and light while retaining that selection; the system reported UI modes `0x21` and `0x11` respectively. The API 26 image has no installed TalkBack service: its accessibility evidence is the exposed hierarchy and reachable controls, whereas the TalkBack interaction checks ran on API 37.2.

At 200% text in short landscape windows, both API 26 and API 37.2 allowed scrolling to all four saved-book action targets and the Settings controls. The final partially exposed row was scrolled fully into view before its action was accepted as reachable; its menu opened on API 26. Settings retained access to theme choices, Downloads and About, with device colors present only on the newer platform. API 26 also displayed the complete, wrapping About heading in narrow portrait. Both platform fixture comparisons retained all four books, 54 tracks, four progress records, zero bookmarks, three source records and the hashes of all 12 downloaded-audio files.

Player layout remains on hold. Full Figma parity, remaining non-player error and accessibility workflows, playback, Android Auto and physical-device qualification are not established by these checks.

### Download progress and recovery presentation — 2026-10-25–26

Active downloads with unknown total sizes now expose Material's indeterminate progress indicator while retaining actual saved-track counts. Queued, paused, failed and removing states do not animate as active transfers. Retrying a failed Downloads load immediately returns to loading until an observed result arrives, instead of briefly presenting an empty collection. A normal lifecycle return retains successfully loaded content. Independent review found no actionable issues.

Five new ViewModel tests cover deferred retry with populated/empty results, lifecycle polling cancellation, source-refresh and request error categories, correct-book retry and duplicate-action suppression. The JDK 21 debug build, lint, all **84 JVM tests** and test-APK assembly passed; lint reported **0 errors, 9 existing warnings and 1 hint**. The initial 512 MiB metaspace cap was insufficient for lint after adding Compose instrumentation dependencies; the successful retry retained one worker and a 1536 MiB heap with a 768 MiB metaspace cap. Builds and emulators ran sequentially.

The instrumentation setup uses the pinned Compose BOM and the v2 Compose test rule. Espresso is explicitly pinned to stable **3.7.0** for Android tests: Compose's transitive 3.5.0 dependency failed during input initialization on API 37.2, before app assertions. The stable version replaces the removed reflective `InputManager.getInstance` lookup, as documented in the [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0). This pin changes no application runtime dependency.

All **seven `DownloadsContentTest` cases passed on both API 26 and API 37.2** with the final dependencies. They exercise queued/downloading/paused/failed/completed action labels and callbacks, unknown versus known progress semantics, load and per-book errors, busy/removing restrictions, disconnected playback, per-track details and confirmed/cancelled removal. A dark, state-only **320 × 320 dp** surface at 200% text verified scrolling to each transfer action, full visibility of its native container and a 48 dp touch target. Screenshots were inspected separately; they are audit captures, not golden-image comparisons. The actual API 37.2 application also opened its empty Downloads route and returned to My books.

The first capture caught an Android Activity transition; final captures disabled emulator window animations. The first stronger bounds assertion incorrectly equated the native 40 dp visible container with its 48 dp touch target. The corrected test checks clipped versus unclipped visual bounds and touch dimensions separately. These test-driver corrections did not change application layout. Both task emulators were stopped after testing.

These controlled UI and ViewModel checks do not establish live provider/Media3 failure recovery, TalkBack interaction with every transfer state, physical-device behavior or full Figma parity. Player layout remains on hold and the sleep timer remains unfinished.

### Focused player, sleep timer and book removal — 2026-10-26

The user lifted the player-layout hold and supplied the target screen, overflow and sheets. Figma node `57:1331` was read through MCP. Now playing follows its fitted artwork, centered identity and transport/tool arrangement using [Media3 Compose controls](https://developer.android.com/media/media3/ui/compose) and [Material bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets). The current provider model exposes tracks; the UI does not claim embedded chapter information. Approved main navigation remains unchanged.

The sleep timer belongs to the one playback service and supports 15/30/45/60 elapsed minutes, end of track and cancellation. Separate removal confirmations explain that removing audio preserves saved identity/history, while removing the book also deletes its tracks, progress and bookmarks. Full removal reserves the book against new playback, clears its queued media and drains writes before verified audio cleanup and database deletion. Independent review caught and resolved a reservation that was previously released on controller disconnect and an ordinary-download busy state that disabled native Pause. Review found no remaining confirmed correctness blockers. No schema migration was introduced.

The final JDK 21 debug build, lint, JVM tests and test-APK assembly passed in **1m 2s**, using one Gradle worker and a 1536 MiB heap. All **107 JVM tests** passed; lint reported **0 errors, 9 existing warnings and 1 hint**. API 26 and API 37.2 each passed all **17 player/removal instrumentation tests**, covering native controls and sheet callbacks, download-state honesty, timer integration with ExoPlayer, progress-write draining, disconnected reservation cleanup and durable Room deletion. Builds and emulators ran sequentially, and both task emulators were stopped after testing.

The API 37.2 application opened the four-book fixture with real fitted covers, resumed and paused downloaded audio, and displayed the options, information and compact download sheets. A 15-minute timer remained active after Home and return; cancellation cleared it. At 200% text in an actual 640 × 320 dp window, scrolling reached the transport/tools, complete information/source action, download action, track list and timer presets/end-of-track option. The download confirmation heading initially wrapped and crowded its explanation; it now uses the existing concise “Remove audio?” wording, while the action remains “Remove downloads.” On API 26, both removal dialogs kept Cancel/Remove reachable and scrolling exposed the preservation/deletion explanations at the same requested window and text scale.

Live removal checks used emulator fixtures only. Removing the Ukrainian recording's download retained its saved identity, track and exact progress. Removing the paused Ken Follett book with an active timer returned to the three-book collection; the media session reported a stopped, empty queue. A database comparison including SQLite's WAL confirmed that this book, its track and progress were gone, the remaining 53 tracks and three progress records were unchanged, and all three library records were preserved. Both download records and all 12 managed audio files (57,972,201 bytes) were removed. The fixture had no bookmarks; the separate Room instrumentation test populated bookmarks and verified their cascade, durable deletion, unrelated-book/provider preservation and fresh history after re-adding.

These checks do not establish physical-device audio output, Android Auto, headset/audio-focus behavior, exhaustive TalkBack coverage or a connected two-controller deletion race. The reservation lifecycle test exercises local cleanup across disconnection. Timer expiry tests advance a clock or use short real media; they do not wait 15 real minutes. Timers are intentionally not restored after service/process death. Screenshots are inspected audit captures, not golden-image comparisons, and the complete Figma/state audit remains open.

### Mini-player timing and final design audit — 2026-10-26

A fresh MCP read of all six main Figma frames found the mini-player's missing elapsed time and duration (`34:1256`). Its Material list item now uses Media3's native `TimeText` observer beside the real track title and retains the native Play/Pause button and fitted cover. Unknown duration and a disconnected controller show `--:--`. Provider track names ellipsize after one line; timing can wrap independently to two lines so a long name cannot consume the whole short window. Observation remains local to the subtitle, with no additional global playback ticker. The pinned Media3 1.11.1 `MiniController` has fixed title/artist content and private cropped artwork, so it cannot supply these accepted slots through its public API.

Independent review found and resolved unbounded track-title growth; device testing then caught timing-width clipping at 200% text. The native flow now gives timing its constrained intrinsic preferred width. Follow-up review found no remaining confirmed blockers. The final JDK 21 build, lint, JVM tests and test-APK assembly passed in **44 seconds**. All **107 JVM tests** passed; lint reported **0 errors, 9 existing warnings and 1 hint**. An initial Compose test run exposed listener cancellation on the instrumentation thread during v2 test teardown. The tests now dispose the player-bearing composition and wait for normal UI cleanup before releasing the player. They explicitly enter keyboard mode before testing focus and distinguish the native button’s 48 dp touch target from its 40 dp visual container. These harness corrections did not change production behavior.

All **six focused mini-player Android tests passed on each API 26 and API 37.2** (12.504 and 14.973 seconds respectively). They cover paused seeking, track transitions, unknown/disconnected timing, independent row and Play/Pause actions including keyboard activation, compact layout, and an 800-character track title in a 320 × 320 dp parent at 200% text. The large-text checks assert actual text layout has no timing overflow and the native button retains its 48 dp touch target; captures from both platforms were inspected. API 37.2 needed a clean emulator startup; its first instrumentation process was killed for low memory before any tests ran. The unchanged test APK passed after startup settled. Builds and the two emulators ran sequentially, and owned emulators were stopped afterwards.

The API 37.2 application displayed actual paused track timing in the mini-player and opened the player without starting playback. A live unmatched Browse search displayed “No books found.” Opening a catalogue book offline rendered the unavailable state and Try again action. That particular partner book continued to fail after connectivity returned; live recovery is not claimed. The existing details ViewModel test verifies failure-to-loading-to-success retry using a recovering fake provider. Diagnosing the actual partner request/playlist failure is separate provider qualification.

Actual TalkBack focus, native touch exploration, linear swipes and double-tap activation on API 37.2 verified source selection and restoration, source-menu opening, the Book details track sheet, mini-player navigation, a 15-minute sleep timer and its cancellation, and both removal confirmations. Closing the chooser, source menu, track sheet and player tool sheets restored focus to their originating controls. Displayed speech identified the mini-player timing, source checkbox, track row and the distinct book-deletion versus audio-only explanations. Both removal confirmations were cancelled through TalkBack. The speech overlay was moved away from the controls being tested; hierarchy dumps were excluded during focus checks because they restart accessibility services. No audible-output claim is made.

After the live checks, all four books, 54 tracks, zero bookmarks, three library records and hashes of all 12 managed audio files were unchanged. All four progress rows remained; only the intentionally played book's position and update timestamp changed. The library selection was restored. See [the design acceptance record](UI-DESIGN-ACCEPTANCE.md) for the six-screen mapping and approved native adaptations.

### Partner recordings with multiple sections — 2026-10-26

Two live partner books that failed inspection returned valid playlists with several named ranges of one MP3. The parser now validates and persists those ranges, and the service applies native Media3 clipping. Logical tracks retain separate IDs and section-relative progress while sharing one physical download and cache key. Schema 7 adds three fields without rewriting existing rows. Source renewal preserves saved boundaries and legacy whole-file behavior. Independent review caught and resolved ambiguous shared paths pointing to different transport URLs; the final review found no remaining confirmed defects in the change.

The JDK 21 debug build, lint, JVM tests and test-APK assembly passed in **1m 59s**, with one Gradle worker and a 1536 MiB heap. All **114 JVM tests** passed; lint reported **0 errors, 9 existing warnings and 1 hint**. The new four-section parser regression failed before the implementation and passed afterwards. All **20 focused Android tests passed on each API 26 and API 37.2** (2.040 and 10.442 seconds). These cover populated v6-to-v7 migration, range persistence and renewal rollback, native clipped playback and seeking from one cached WAV, section-relative progress, shared download restoration, pending additions, orphan cleanup, legacy metadata and saved-book deletion. Builds and emulators ran sequentially; both task emulators were stopped after testing.

On API 26, the upgraded four-book fixture retained its existing library and downloads. Live Book details loaded “Чурилов Виктор - Мои пернатые соседи” (four sections), “Ги Де Мопассан - Два приятеля” (one track), and the previously failing “Вентворт Патриция - В тихом омуте” (43 sections). The four-section book streamed its second section with a 3:17 duration and downloaded **one 13,811,085-byte MP3**. With Wi-Fi and mobile data disabled, no active default network, and the application force-stopped and reopened, it resumed the second section and opened the third with its correct 4:08 duration.

Removing downloads preserved every saved database row, including listening progress, while deleting the shared resource. Removing the book deleted its four tracks and progress, both after audio-only removal and when its downloaded audio was still present. The other four books, 54 tracks, four progress rows, three library records, two download records and hashes of all 12 existing audio files were unchanged. Bookmark deletion is covered by populated instrumentation fixtures; the live fixture had none. A separate pre-existing UI issue was recorded: returning from player deletion to an existing Book details destination can retain an outdated saved badge despite successful durable deletion.

These checks establish metadata loading and emulator playback/cache behavior for the sampled recordings. They do not establish physical-device audio output, Android Auto, headset/audio-focus behavior, arbitrary provider compatibility or MP3 seek accuracy for every encoding. No GUI layout or navigation changes were made in this fix.

### Loaded cover framing — 2026-10-26

The shared cover component now makes its placeholder background transparent after a successful image load. Previously, square artwork fitted inside the 56 × 80 dp list slot exposed colored strips above and below the image. Figma My books node `34:1126` uses fitted artwork with a transparent wrapper. Loading, missing and failed artwork retain the existing placeholder frame and book icon.

The debug build, lint and all **114 JVM tests** passed in **1m 44s**; lint reported **0 errors, 9 existing warnings and 1 hint**. Independent review found no actionable issues. API 26 visual checks covered My books and live Browse in light and dark themes, plus separate null-URL and failed-file artwork fixtures. Compared screenshot pixels confirm that the former top/bottom strips now match the row background while sampled square and portrait artwork pixels remain unchanged. These are inspected captures and focused pixel comparisons, not a golden-image suite. The owned emulator was stopped afterwards.

### Mini-player separation and dark reference — 2026-10-26

Fresh Figma reads of light mini-player instance `73:6546`, dark board `75:7666`, dark My books `75:7670` and its mini-player `75:7792` confirmed a stronger full-width band directly above navigation. The reusable base component still had its earlier color, so the current screen instances supplied the visual target. The native Surface and its inner ListItem now share `surfaceContainerHighest`; navigation retains its Material container color. This semantic adaptation follows purple and wallpaper themes. The dark board is recorded with the accepted design references.

Independent review found no actionable issues. The JDK 21 debug build, lint, JVM tests and test-APK assembly passed in **1m 34s** with one Gradle worker and a 1536 MiB heap. All **114 JVM tests** passed; lint reported **0 errors, 9 existing warnings and 1 hint**. All **six existing mini-player Android tests passed on each API 26 and API 37.2** (4.213 and 16.943 seconds), including row/button and keyboard actions, live timing, compact layout, and a very long provider title at 200% text. The large-text captures from both platforms were inspected. Builds and emulators ran sequentially, and task emulators were stopped after verification.

The API 37.2 application opened and paused a downloaded fixture book and displayed the mini-player in My books and Browse. Inspected captures confirmed separation above navigation in purple light/dark and wallpaper light/dark themes. Sampled mini-player/navigation pixels were respectively `#E6E0E9`/`#F3EDF7`, `#36343B`/`#211F26`, `#E1E2ED`/`#EDEDF6`, and `#23262E`/`#17191F`. These are focused visual and pixel checks, not golden comparisons or a full audit of every state in the new dark board.

### Book details through deletion and audio failures — 2026-10-26

Book information now renders independently of audio resolution. A story or playlist failure retains the title, cover and description and exposes a focused **Retry audio** action near Tracks. Save, Listen and Download become available only after a valid nonempty playlist resolves. The native controls retain the existing light/dark Figma composition (`57:1255` and `75:8242`); an unresolved lookup no longer appears as zero tracks. Partner audio retry reuses the inspected metadata without another review request.

Book details also observes full deletion while visible and reconciles it on return from the player. It clears the saved badge and listening history while retaining provider information for an explicit fresh save. Deleted local-only identities become unavailable. Independent review caught a pending-action race that could republish a stale save response after deletion. Three regressions failed before the correction; pending actions now cancel on observed deletion and use current canonical collection membership. The follow-up review found no remaining confirmed issues. Schema 7 and the single service-owned player remain unchanged.

The final JDK 21 debug build, lint, JVM tests and test-APK assembly passed in **1m 10s**, with one Gradle worker and a 1536 MiB heap. All **137 JVM tests** passed; lint reported **0 errors, 9 existing warnings and 1 hint**. All **six focused Android tests passed on each API 26 and API 37.2** (3.997 and 18.317 seconds respectively). These cover metadata during audio loading/failure, independent retry callbacks, resolved action availability, a 320 × 480 dp surface at 200% text, durable saved-book deletion and removal-guard cleanup. Captures from both platforms were inspected; they are audit captures, not golden comparisons. Builds and emulators ran sequentially, and both owned emulators were stopped afterwards.

On API 26, live catalogue and saved-book details both returned to **Not saved to My books** after player removal. Explicitly saving the provider book again retained its four tracks and created no old listening-progress row. The other four saved books, 54 tracks, four progress rows, three libraries, two download records and hashes of all 12 existing audio files remained unchanged. A controlled emulator-only network rule then rejected the sampled book's playlist host while leaving its metadata API reachable: real title, cover and description stayed visible with **Couldn't load audio** and **Retry audio**. Removing the rule and retrying recovered its one track without saving the book. The rule was removed and verified absent after the check.

These checks cover the sampled providers and controlled emulator states. Physical-device audio output, Android Auto, headset/audio-focus behavior and exhaustive accessibility or provider qualification remain separate work.

### Redundant author prefixes in book titles — 2026-10-26

A shared presentation formatter removes a leading author only when the entire name matches the separate author field and a spaced hyphen, en dash or em dash precedes a nonblank book title. Case, whitespace including nonbreaking spaces, and canonically equivalent Unicode names can match; aliases, reordered names, partial matches and punctuation differences are not guessed. Comparison normalization preserves the original suffix characters and exact raw fallback. Existing saved books receive the correction through derived getters without rewriting Room records or schema 7.

Catalogue and saved rows, details, player/download sheets, accessibility/removal/share labels and Media3 book/album metadata use the same display title. Title sorting and its tie breakers follow that title. Provider Default ordering, outbound queries, actual track titles, stable IDs and listening history remain unchanged. The current Figma Book details reference (`57:1255`) was read through MCP; this correction changes supplied text without changing native controls, layout or assets.

Independent review found no remaining confirmed issues. The final JDK 21 debug build, lint and all **148 JVM tests** passed in **1m 8s**, with one Gradle worker and a 1536 MiB heap. Lint reported **0 errors, 9 existing warnings and 1 hint**. Nine formatter tests cover the supplied Cyrillic example, raw metadata retention, whitespace/dash variants, complete author matching, ambiguous inputs, exactly one removal, canonical Latin/Cyrillic equivalence and original suffix preservation. Catalogue and collection regressions verify display-title ordering without mutating source entries. The initial focused regression run failed as expected before the correction; the Unicode cases also failed before their implementation. No additional emulator, physical-device or Android Auto runs were performed for this text-formatting change.

### Incoming book links and service integration — 2026-10-26

`ACTION_SEND` text/plain and `ACTION_VIEW` now feed an Activity-owned ViewModel with bounded scalar input, saved pending delivery state, cancellation and generation guards. Resolution checks retained source and canonical saved identities before provider work. Native registration, loading and recoverable-error dialogs retain explicit actions. Successful delivery navigates once to existing Book details; repeated delivery to the current identity does not stack it, while a new delivery after Back can reopen it. Registration prompts and source selection remain distinct. LibriVox.org page resolution validates same-source RSS/canonical identity without saving.

Independent review found no remaining confirmed issues. The final JDK 21 debug build, lint, JVM tests and test-APK assembly passed in **1m 29s**, with one Gradle worker and a 1536 MiB heap. All **174 JVM tests** passed. Lint reported **0 errors, 11 warnings and 1 hint**; two additional warnings concern deliberately unverified HTTPS filters. No domain association or automatic interception of another package's redirect is claimed. A subsequent test-only text-geometry correction compiled in **19s**.

All **13 focused Android tests passed without skips on each API 26 and API 37.2** (15.442 and 22.148 seconds). Seven cases cover native incoming-link content, three cover Intent extraction and three operate the actual MediaLibraryService. Compact dialog cases verify complete text-line geometry, scrolling, heading semantics and actionable labels at 200% text. Initial visual inspection found that a fixed title left less than one message line in a short landscape window; the corrected dialog scrolls its title with the message while retaining native buttons. Text-width checks use actual line edges and one-pixel rounding tolerance rather than a paragraph allocation flag.

Live checks on both platforms covered the supplied title-plus-HTTPS share, implicit legacy custom-scheme launch, cold/warm delivery, duplicate suppression, reopening after Back, rotation, old-PID-verified process death, a different link after death, task restoration and unknown-source cancellation. A registered, deselected source routed directly to Details without consent or reselection; the sampled LibriVox ID itself was unavailable, so that case establishes routing rather than successful metadata lookup. On API 26 an initial hierarchy attachment during window teardown triggered a framework accessibility exception; controlled reruns attached after settled windows and passed. The earlier `am kill` attempt left its process alive and is not counted as process-death evidence.

Fixture comparisons retained all original columns in four books, 54 tracks, four progress rows and three source records, including source selections, plus two completed download records and hashes of all 12 existing audio files. Inspection saved no new book. The first comparison also exercised the existing additive schema 6-to-7 migration. These are bounded fixture comparisons and inspected captures, not a golden-image suite.

The service tests establish actual root/book/track browsing and pagination for app-owned browsers, rejection and rollback of unknown IDs or supplied media locations, and exact idle indexed seek/speed persistence across controller reconnection. They do not prepare or play audio, use a separate UID, establish native car-host compatibility or qualify physical audio interruptions. Those remain separate playback/download qualification work.

### Optional reader credits — 2026-10-26

Verified LibriVox section reader IDs/names now populate bounded per-track credits, with unknown and explicitly empty attribution kept distinct. Optional malformed data does not invalidate valid book information or audio. The sampled partner responses had no separate narrator field, so no attribution is inferred from their authors. Existing native Book details, player information and track sheets show **Read by**; a book-wide summary requires complete, identical credits on all tracks. Metadata refresh updates the active player's book and tracks together with lifecycle, generation and removal guards.

Room schema 8 adds only nullable `tracks.readerCreditsJson`; schemas 1–7 remain unchanged. A saved metadata refresh fetches one extended LibriVox response, parses credits without requiring playable audio URLs, and updates them atomically only after the entire saved section-reference set matches. Missing values and refresh failures preserve known credits; explicit empty lists clear their track. Transport renewal preserves attribution, identity, bounds and listening history. Existing saved books with cached descriptions acquire credits through **Refresh details**, rather than an automatic new network request on every open.

Independent review found no confirmed issues. The final clean JDK 21 debug build, lint, JVM tests and test-APK assembly passed in **1m 19s**, with one Gradle worker and a 1536 MiB heap. All **190 JVM tests** passed with no failures or skips; lint reported **0 errors, 11 warnings and 1 hint**. Temporary service diagnostics used during separate controller qualification were removed; the playback callback matches the prior committed implementation.

All **39 focused Android tests passed without skips on each API 26 and API 37.2** (28.179 and 72.707 seconds). They include populated schema 7-to-8 migration with progress/bookmarks/resource identity, save/reopen and transport preservation, metadata-only refresh with invalid audio, omission/empty/mismatch/offline behavior, full transaction rollback, historical storage/deletion regressions and native reader-summary/per-track states. Initial failures were test assumptions: an old registration count omitted intentional provider revalidation, and an author query matched the underlying player as well as its information modal. Their corrected assertions retain the identity/history and separate-author checks.

On both platforms an older schema 6 fixture migrated to 8 and explicit refresh of saved LibriVox book 11455 populated all 29 tracks with **Roger Melin**. Inspected Book details and track-sheet captures retained the separate author **Ray Stannard Baker**. Comparisons preserved all original identity, transport, listening-history and source-selection fields, two completed download records and hashes of all 12 existing audio files; only the intended optional metadata could change. These captures and fixture comparisons are bounded evidence, not golden screenshots or an exhaustive provider/accessibility audit.

### Resuming capped HTTP download responses — 2026-10-26

Some origins cap each HTTP 206 response even when a resume request asks for the complete remaining recording. The download upstream now validates `Content-Range`, exposes the advertised resource remainder and rejects premature EOF instead of letting a single response shrink the cached resource length. Media3's existing progress-aware retries continue at the next uncached byte. Contradictory headers, wrong offsets, overflowing lengths and a smaller total than a known requested extent fail honestly. Ordinary full responses, Media3's handling of ignored ranges, and genuine unknown-length full responses retain their existing behavior. Playback continues to use its existing HTTP source; no player, cache, download manager or schema is replaced.

The regression first reproduced false completion at **1,241 bytes instead of 5,123** through a real Media3 download manager and persistent cache. The final fixtures require ten capped responses, including a reopened partial cache and a download beginning without cached bytes, and compare the complete offline bytes. All **13 focused Android cases passed on each API 26 and API 37.2** (0.874 and 8.715 seconds): nine download validation cases and four existing shared-recording regressions. The HTTP fixture models the response contract; its ignored-range case assumes Media3 has already skipped the offset rather than exercising a real HTTP server's 200 response.

Independent review found no remaining confirmed issues. The final JDK 21 debug build, lint, all **190 JVM tests** and test-APK assembly passed in **1m 23s**, with one worker and a 1536 MiB heap. Lint reported **0 errors, 11 existing warnings and 1 hint**. No dependency pin or persistence migration changed.

Actual provider checks on both platforms retained nonzero partial bytes and explicit pause across verified process death. Network loss left the recording queued and unavailable offline; network return completed the **56,139,276-byte recording**, matching the original bytes while preserving listening history and the unrelated downloaded recording. On API 26, a reboot with the scheduler's receiver setting confirmed persisted also resumed automatically on network return without opening the app. On both platforms, with no active network, the new cache played and sought beyond 18 minutes through the shared media session. These checks concern the provider's returned recording, not the completeness of the publisher's audiobook or audible physical-device output.

An abrupt API 26 reboot before Android persisted WorkManager's receiver enable setting left the queue awaiting an explicit app open. On API 31+, the [pinned Media3 service deliberately ignores its scheduler](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/offline/DownloadService.java#L598-L603) because of background foreground-service launch restrictions; the missing API 37.2 scheduler job is therefore expected. These remain separate recovery considerations: the settled API 26 result does not establish arbitrary power-loss or current-platform reboot recovery. This fix also cannot infer an earlier truncated completed entry's original size while offline; removing that book's downloads and downloading again obtains fresh audio while preserving its saved book and history. Low-storage, physical headset/Bluetooth and full Android Auto qualification remain open.

### Native download restart scheduling and storage pressure — 2026-10-26

On API 26–30, download restarts now use Media3's `PlatformScheduler` with one stable, persisted Android job. Its standard job service requires `BIND_JOB_SERVICE`; the actual download service remains private. This avoids depending on WorkManager's asynchronous boot-receiver enablement. Keep the Media3 WorkManager adapter during the transition: an earlier installed build can still have a queued worker referencing those components. This introduces no second player, cache or download manager.

Media3 1.11.1 deliberately ignores the scheduler hook on API 31+ because of background foreground-service launch restrictions. Retain that platform policy. A persisted queued index entry alone also does not prove the downloader is idle: an in-flight HTTP read can finish cancellation later, before Media3 registers its restart job. Neither the scheduler configuration nor an emulator reboot establishes universal power-loss recovery or recovery after the listener force-stops the app.

Independent review found no confirmed issues. The final bounded JDK 21 build, lint, JVM checks and test-APK assembly passed in **12s**, reusing valid Gradle cache outputs. Reports contain **190 JVM tests with no failures, errors or skips**; lint reports **0 errors, 11 warnings and 1 hint**. All **41 data/playback Android tests passed without skips on each API 26 and API 37.2** (12.403 and 22.826 seconds). The added cases verify persisted native-job identity, cancellation by a recreated scheduler, system binding protection and the private restart handler.

Actual API 26 checks used a nonzero partial download and confirmed the pending native job before reboot. Both the run with no additional settling delay and a second run with 15 additional seconds resumed automatically when the network returned, without opening the app. Both recovered the complete recording with identical bytes and preserved listening history and unrelated audio. A separately paused partial download remained paused through real reboot and app opening. An upgrade check also queued work under the previous APK, installed the replacement, and recovered identical audio on network return without launching the app; this exercises the retained legacy worker path. These checks used the clean candidate; all runtime archive contents and the signing certificate match the final rebuilt APK.

On API 37.2, a paused partial download also survived verified process death, real reboot and app opening without downloading or playing. A queued partial download retained its bytes through a separate reboot and remained unchanged during the ten-second observation after network return. Opening the app and choosing its native Resume action recovered the complete recording with identical bytes, preserved history and retained the unrelated download. This establishes the sampled explicit recovery path, rather than automatic background recovery on current Android.

A separate API 26 emulator with a 2 GiB data volume and a verified 32 MiB root reserve exercised an actual audio-cache `ENOSPC` write failure. The download persisted a failed state at 25,149,162 of 56,139,276 bytes and exposed Retry without claiming offline availability. After freeing the bounded test allocation, native Retry recovered the complete recording with identical bytes. All four saved books, 54 tracks, four progress records, the empty bookmark table, three library records and the unrelated downloaded recording were preserved. The temporary allocation was removed and normal emulator access and network settings restored. This qualifies the sampled storage failure and recovery; it does not establish behavior on every Android version or filesystem.

### Multi-file and shared-recording downloads — 2026-10-27

Actual API 26 and API 37.2 checks downloaded all **29 separate audio resources** for the saved LibriVox example, totalling **422,721,024 bytes**. Every reconstructed cached resource matched the provider-published SHA-1 and MD5 values, with positive known lengths and complete contiguous spans. The checks exercised nonzero partial downloads, native pause, verified process death, network loss and recovery, whole-book availability, and confirmed download-only removal. Saved book/track/history/source records and unrelated downloaded audio remained unchanged. The API 26 run's final verifier initially demanded immediate deletion of empty SQLite cache keys; actual payload and download-row removal passed, and an independent cold cache initialization also cleared those deferred keys. The corrected verifier passed the complete current-platform sequence.

A real partner book with **four timed sections sharing one MP3** passed the complete sequence on both platforms. Exactly one persistent download request retained all four canonical logical track IDs. Partial pause, verified process death and network interruption preserved that mapping and the retained bytes. Completion recovered **13,811,085 bytes** matching the independently fetched recording's SHA-256, and all four tracks became available offline. Without an active network, a separate trusted controller used the existing service session to play the second section with a **197,000 ms** duration, seek relative to that section, and move to the third section with a **248,000 ms** duration. Removing downloads retained the new book, its four tracks and listening progress; full book removal then returned the fixture to its exact original records and audio hashes. Temporary listener access and network settings were restored.

Resume was accepted online before connectivity was removed: incomplete downloads refresh their provider transport before starting. These checks do not establish queuing a new Resume request while the provider is unreachable. Limited-bandwidth emulator runs stalled and are excluded from the passing results; the network-interruption checks used normal speed and actual connectivity removal/restoration. Physical audible output, headset/Bluetooth routes and a real Android Auto/DHU connection remain separate qualification requirements. No application code changed for this qualification.

## Release preparation

The [release workflow](RELEASING.md) validates stable version-tag pushes and
publishes a [GitHub Release](https://github.com/drolevar/audiotales/releases) with
the exact signed APK, checksums, source/version provenance and R8 mapping,
retaining the Actions artifact for 30 days. Signing credentials are supplied
only after the unsigned release build, release lint and shared-source JVM tests
pass. A separate publishing job verifies all draft assets before publication
and fails closed on existing Releases. Ordinary branch/PR CI stays unsigned.
Keep signing keys and credentials outside Git; store publishing and a public
repository transition remain separate decisions.

Local validation on 2026-10-28 built the minified `0.2.0` candidate, ran release
lint and all 190 JVM tests with no failures, errors or skips. The normal debug
build/lint/test gate also passed. Both lint variants reported zero errors, 11
warnings and one hint. Six release-tooling tests and actionlint 1.7.12 passed.
Actual signed candidates passed certificate, package/version, non-debuggable,
ZIP alignment and ELF load-segment checks for all eight native libraries; a wrong
signer was rejected. These were local validation builds with a test version
input, not a pushed release tag or a GitHub-produced release artifact. A real
tag-triggered result is recorded below.

The first minified cold launch exposed a startup failure in WorkManager 2.8.1's
transitive Room 2.5 database: R8 removed `WorkDatabase_Impl`'s reflected public
no-argument constructor. A rule preserves only that constructor; dependencies,
normal startup and existing WorkManager jobs remain intact. After rebuilding,
the actual signed/minified candidate cold-launched on API 26 and API 37.2,
registered the sample partner library, resolved its book information and track,
and started and paused a short stream through the app's shared service. This is
headless runtime evidence for the sampled paths, with no physical-audio or Auto
claim. Packaging checks alone did not detect the original failure.

The first tag-triggered signed build, [v0.2.0](https://github.com/drolevar/audiotales/actions/runs/40661487817), succeeded on 2026-10-28 from commit `46f648b263fa6c8271edeb255eb9411e07c6f8ac`. The downloaded artifact's ZIP digest, file checksums, version/signature provenance and native alignment were independently verified. The exact GitHub-built APK then passed cold launch, sample partner-library registration/book lookup, short streaming and native Pause on API 26 and API 37.2. These checks cover the sampled headless release paths; physical audio and Android Auto acceptance remain separate. The same verified APK is now attached to the [v0.2.0 GitHub Release](https://github.com/drolevar/audiotales/releases/tag/v0.2.0); its downloaded release asset matches the tested SHA-256.

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
