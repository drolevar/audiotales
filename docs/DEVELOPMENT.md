# Development guide

Start with [Decisions and scope](DECISIONS.md) for the agreed direction. The repository's main README is written for listeners; keep development details in `docs/`.

For UI work, follow the two [accepted Figma design references and native-control policy](DECISIONS.md#12-ui-design-and-implementation-references--accepted-2026-10-25). Inspect the relevant screen and reusable components through Figma MCP before implementing them in native Compose. Native Android behavior takes priority over exact visual fidelity; use Google's existing controls where they cover the required behavior, including Media3's Compose playback controls. The [UI architecture proposal](UI-ARCHITECTURE.md) describes the recommended translation and identifies screen-level choices that remain proposed.

## Current implementation

The development build implements the first online-library flow: register a compatible HTTPS base URL, browse/search its catalogue, inspect a book and save it to My books. Browse owns catalogue discovery and search; My books contains saved books. Book-URL entry has been removed from the UI. Browse groups independently paged results from selected libraries, with per-source failures, native source selection, shown-result sorting and real-language filtering. Manage libraries supports adding, renaming, sharing and removal; individual-library browsing does not change the selected set. Hidden source records remain available to saved-book refresh, and re-adding their base URL restores the same identity. Library instances are persisted separately from protocol adapters. The shared partner protocol and LibriVox.org's public API are separate adapters; arbitrary websites are not automatically compatible.

Book details is a full secondary screen reached from catalogue rows or the saved-book menu. Inspecting a catalogue book does not save or play it. Explicit Save, Listen and Download actions use the canonical saved identity. Saved details render locally and can refresh optional description/language/artwork without renewing tracks. The track sheet uses actual provider entries; embedded chapter extraction remains future work.

Room 3 schema versions 1–6 preserve books, ordered tracks, listening progress and bookmarks while adding provider identity, registered libraries, per-track request headers and saved artwork URL/referer metadata, durable library selection/registration flags, and nullable saved description/language. Keep all exported schemas in version control. Source refresh updates only transport URLs/headers after validating the entire stable track set; it preserves IDs, order and listening history.

One service-owned Media3 ExoPlayer supplies phone controls, background playback and external controllers. The phone uses Google's Media3 Compose controls. Progress is saved periodically and at playback transitions; opening a book does not autoplay. Explicit Retry refreshes online sources and rebuilds the queue from saved IDs. Completed offline books do not require a provider lookup.

Media3 DownloadManager/DownloadService own explicit downloads and a shared persistent cache. Queued, downloading, paused, failed and completed states come from that subsystem. Offline availability requires every required track to be complete with all expected bytes cached. Removing downloads retains library identity and progress. Streaming does not silently populate the managed download cache.

Material 3 adaptive navigation exposes My books, Browse and Settings. My books has progress-aware rows, All/In progress/Downloaded filters and Recent/Title/Author sorting. A secondary Downloads screen, reachable from saved rows and Settings, shows managed transfers, real track/byte progress, download details, pause/resume/retry and confirmed removal. Theme and device-color preferences persist in DataStore; real artwork is fitted without cropping in catalogue results/details, saved rows, the player and mini-player. Partner artwork requests include the library referer required by the image host. A bounded metadata-only refresh fills missing artwork for existing saved books without changing tracks or listening history; artwork failures do not block playback. Local SAF import, embedded chapters, bookmark UI, sleep timer, reporting and release signing remain follow-up work. Android Auto declarations are present but car compatibility has not been verified.

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

These checks do not establish the full error-state matrix, TalkBack coverage for other screens or transfer states, physical-device behavior, audio output, Android Auto, or full Figma parity. The API 26 keyboard check was obscured by Gboard's first-run tip and is not counted as verified. The remaining input-dialog and constrained-window matrix stays open. Player layout remains on hold at the user's request, and the sleep timer remains unfinished.

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
