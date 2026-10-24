# Decisions and scope

Recorded on 2026-10-24 before Android scaffolding.

This is the durable record of the choices made for AudioTales. **Accepted** means the product or technical direction has been agreed. **Implementation choice** records a reversible choice made for the initial scaffold. **Proposed** and **deferred** items are not commitments or implemented features.

## 1. Product and distribution — accepted

AudioTales is a native Android audiobook player, with Android Auto support as a first-class requirement. It is built for listening to a personal audiobook library, including local M4B books and books split across MP3 files.

The intended listening features are reliable resume, chapters, bookmarks, adjustable playback speed, a sleep timer, offline listening, and car controls. Remote catalogue access and managed downloads fit the architecture; a particular catalogue provider has not been chosen.

The project lives at [drolevar/audiotales](https://github.com/drolevar/audiotales). It is private during development and uses the [MIT license](../LICENSE). A public release date, store distribution, signing identity, and a date for making the repository public have not been selected.

## 2. Documentation audiences — accepted

- `README.md` is for listeners: what the app offers, its availability, compatibility, and how to get help.
- `docs/` contains development setup, architecture, decisions, implementation status, and contributor guidance.
- Describe unfinished features as planned. A scaffold or a successful build does not establish production readiness or verified Android Auto compatibility.

The [development guide](DEVELOPMENT.md) records the current implementation and verified build commands. Update this decision record when the direction changes instead of silently replacing past decisions.

## 3. Native Android stack — accepted

| Concern | Choice | Purpose |
| --- | --- | --- |
| Language | Kotlin | Native Android implementation |
| UI | Jetpack Compose and stable Material 3 | Accessible, adaptable screens with project branding |
| Navigation | Navigation 3 | State-driven navigation and back stack |
| UI state | AndroidX ViewModel, StateFlow, coroutines/Flow | Unidirectional data flow and lifecycle-aware collection |
| Dependency injection | Hilt | Explicit dependencies and shared application services |
| Structured persistence | Room | Library metadata, progress, bookmarks, and durable records |
| Preferences | DataStore | Small user settings |
| Playback | AndroidX Media3 / ExoPlayer | Shared playback engine and media sessions |
| API networking | Retrofit, OkHttp, kotlinx.serialization | Future catalogue and reporting APIs |
| Artwork | Coil | Loading artwork when the library exposes it |
| Deferred work | WorkManager | Suitable persisted background work, particularly report uploads |

Use compatible stable releases. Do not add preview dependencies merely to make the version numbers newer. Exact tested pins belong in the Gradle version catalogue and the development guide.

## 4. Minimum Android version — accepted

Keep **minSdk 26 (Android 8.0)**. The user explicitly retained this baseline after discussing the tradeoffs.

A low minimum SDK does not set the compile or target SDK. Compile and target the current stable Android platform, use AndroidX support where available, and guard newer platform APIs with appropriate fallbacks. Raising the minimum SDK needs a documented reason and a new decision.

Android Auto has its own phone, vehicle, and host compatibility requirements; phone-app support on API 26 does not imply Android Auto support on every supported phone.

## 5. Playback ownership and Android Auto — accepted

One `MediaLibraryService` owns one ExoPlayer and its `MediaLibrarySession`. Phone UI connects through a `MediaController`. The library, phone controls, car controls, system media controls, and notification all operate on the same playback state.

Expose a browsable media library through the standard Android Auto media integration. Custom car templates are deferred. Handle audio focus, headphone disconnection, background playback, process recreation, and durable progress as core playback concerns.

Use stable book and track identifiers. Resolve playback requests to entries in the app's own library; do not trust arbitrary content URIs received from external controllers. Persist track identity and position, so multi-file books do not rely on a single-file position model.

An empty browsable library is sufficient for the first scaffold. Import, real playback queues, resume restoration, voice search, chapter extraction, and car acceptance testing are implementation milestones.

## 6. Library, files, and downloads — accepted direction

Use the Storage Access Framework for user-selected files and folders, retaining URI grants when available. Keep user-owned source files separate from app-managed downloads, and provide recovery when files move or permissions are revoked.

Room owns structured library state; DataStore owns preferences. The app must not silently destroy listening history during schema changes. Export database schemas and introduce migrations as the schema evolves.

Evaluate Media3 download support for supported sources. WorkManager is for suitable deferred jobs; long user-initiated downloads need an execution strategy appropriate to their size and Android version. The source format, resume behavior, and platform constraints will determine that implementation.

## 7. Convenient automatic reporting — accepted

The user wants automatic submission: a listener describes a problem and taps **Send**, then the app handles diagnostic preparation, upload, and issue creation. A listener should not need a GitHub account, manually create an issue, or attach a ZIP.

The planned flow is:

1. Collect a description/category and relevant app/device environment.
2. Let the listener review the diagnostic information to include.
3. Create a stable client report ID and persist the submission locally.
4. Upload through durable background work with offline recovery.
5. Accept the report durably on a reporting backend and queue GitHub issue creation.
6. Show receipt, pending/submitted/failed status, and a useful follow-up path in the app.

Report acceptance and GitHub issue creation are separate states. Repeated delivery must reconcile uncertain issue-creation outcomes rather than opening duplicates. Do not claim exactly-once submission without that recovery mechanism.

Because the repository is private, a GitHub issue URL cannot be the only report receipt or status interface. An account-free follow-up flow needs a separate high-entropy credential scoped to that report; a public report ID is not authorization.

## 8. Diagnostic privacy and server credentials — accepted

Keep local diagnostics bounded, structured, and redacted. Remove credentials, signed URLs, audiobook contents, and unnecessary personal/library details before upload. Backend validation and redaction provide an additional check.

Detailed diagnostic bundles stay in private storage with authenticated maintainer access. GitHub issues receive sanitized summaries and opaque report IDs. Never place a bearer download link for private diagnostics in an issue.

Use a project-owned GitHub App with the issue-writing permissions needed for this repository. Keep its private key and installation credentials on the backend. Never embed a maintainer token, private key, or shared secret as protection in the APK.

The backend needs request/upload limits, schema validation, rate limits, durable queue/state, and a retention policy. Preserve release identifiers and R8 mappings so diagnostics can be matched to the shipped build.

Automatic crash uploads are a separate, explicit opt-in. If added, group repeated occurrences by defect instead of opening one issue for each crash.

## 9. Reporting infrastructure — proposed, not provisioned

A small TypeScript Cloudflare Worker with private R2 storage and durable queue/state is the current candidate. Hosting, storage retention, operator authentication, abuse controls, and operating costs still need a concrete implementation decision.

No reporting backend, GitHub App, monitoring account, telemetry upload, or production endpoint is provisioned by the Android scaffold. Sentry/Crashlytics or another crash service has not been selected.

## 10. Initial scaffold — implementation choices

Start with one `:app` Gradle module and clear packages for UI, library data, playback, preferences, and diagnostics. Extract modules when dependency boundaries and build costs justify it.

Use `io.github.drolevar.audiotales` as the initial application ID and Kotlin namespace, and **AudioTales** as the app label. Confirm identity before the first externally distributed release.

The first scaffold should include:

- A launchable Compose app with an honest empty library and working navigation.
- Hilt wiring, Room library/progress/bookmark foundations, and a real DataStore preference.
- A single Media3 library service, Android Auto declarations, and controller lifecycle plumbing.
- A Gradle wrapper, a pinned version catalogue, release configuration, and a GitHub Actions build/lint/test workflow.
- Focused validation of meaningful state and boundary behavior, without pretending device/car tests have run.

Implement local import and durable playback next. Downloads, a report UI, the reporting backend, artwork, and catalogue integrations follow as actual features; do not expose nonfunctional Send or download controls.

## 11. Verification and change management — accepted direction

Build and lint the scaffold, run relevant automated tests, and record the exact results. Test on API 26 and a current Android version, then validate car behavior with Android Auto/DHU before claiming support is complete.

Keep build tooling requirements and commands in `docs/DEVELOPMENT.md`. Keep secrets, local SDK paths, generated build output, and signing keys out of version control. Release signing and publishing require their own setup.

For each material decision change, record its date, reason, consequences, and what it supersedes. Proposed services and future capabilities must stay distinguishable from code that is already implemented.
