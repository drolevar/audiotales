# Development guide

## Project status

AudioTales is at the initial planning stage. This document records the intended architecture and implementation priorities. The Android project, build configuration, reporting backend, and release pipeline have not been created yet.

Keep `README.md` focused on listeners. Put setup instructions, architecture decisions, and contributor guidance in `docs/`. The project uses the [MIT license](../LICENSE).

## Platform and stack

The minimum supported Android version is **Android 8.0, API 26**. Use current stable compile and target SDKs when creating the project. Gate newer platform APIs where necessary, and test the fallback behavior on API 26.

| Area | Planned choice |
| --- | --- |
| Language | Kotlin |
| Interface | Jetpack Compose and stable Material 3 |
| Navigation | Navigation 3 |
| State | ViewModel, StateFlow, coroutines, and unidirectional data flow |
| Dependency injection | Hilt |
| Structured storage | Room |
| Preferences | DataStore |
| Networking | Retrofit, OkHttp, and kotlinx.serialization |
| Artwork | Coil |
| Playback | AndroidX Media3 and ExoPlayer |

Confirm compatible stable versions together when scaffolding the app. Record Android Studio, JDK, SDK, and dependency requirements alongside working build commands after the build is verified.

## Playback and Android Auto

A single `MediaLibraryService` will own the ExoPlayer instance and `MediaLibrarySession`. The phone interface will connect through a `MediaController`; it will not create a separate player.

The service will expose a browsable audiobook library and consistent playback actions for Android Auto, system media controls, and other supported controllers. Start with the standard media browser integration. Treat Android Auto compatibility separately from the phone app’s minimum SDK.

Persist playback position, selected book, and relevant playback settings so listening can resume after process death or a restart. Design chapters, bookmarks, playback speed, and the sleep timer around this shared playback state.

## Library and downloads

Use the Storage Access Framework for user-selected files and folders, retaining URI permissions where available. Handle moved files and revoked permissions with a clear recovery path.

Use Room for library metadata, progress, bookmarks, and download records. Use DataStore for preferences. Keep user-owned source files distinct from app-managed downloads.

Evaluate Media3 download facilities for supported media sources. Use WorkManager for suitable deferred work, including report uploads and retries; choose download execution mechanisms according to transfer size, user initiation, and platform requirements.

## Problem reporting

The planned reporting flow lets a listener describe a problem and tap **Send** without a GitHub account.

1. Create a stable report ID and persist the report locally.
2. Upload through a durable queue with offline recovery and bounded retries.
3. Have a reporting backend validate and redact the submission, store diagnostics privately, and create a sanitized GitHub issue.
4. Return a receipt and report status to the app.

The repository is intended to remain private during development. A GitHub issue URL therefore cannot be the only way a listener tracks a report. Provide app-visible status and, if follow-up is implemented, a scoped per-report credential.

Keep GitHub App credentials exclusively on the backend, with issue-writing access limited to the project repository. Never embed maintainer credentials in the APK. Keep local logs bounded and redacted, excluding credentials, signed URLs, audiobook contents, and unnecessary personal or library details. Redact the diagnostic bundle on the device before upload, with backend validation and redaction as an additional check. Let listeners review the diagnostic information they submit.

Detailed diagnostic bundles must require authenticated maintainer access. Retry handling must reconcile uncertain issue-creation results to reduce duplicates. Automatic crash uploads require a separate opt-in.

A Cloudflare Worker with private R2 storage is a candidate backend, not a deployed dependency.

## Implementation order and verification

Begin with library import, reliable local playback, and progress restoration. Add Android Auto browsing and playback next, followed by downloads and reporting.

Keep clear boundaries between the interface, playback service, library storage, downloads, and diagnostics. Split these into Gradle modules when the implementation benefits from it.

Prioritize verification of API 26 behavior, process recreation, permission loss, audio interruptions, chapter transitions, and Android Auto controls. For reporting, verify redaction, offline retries, duplicate prevention, and private diagnostic access. Preserve release identifiers and R8 mappings so reports can be traced to the shipped build.
