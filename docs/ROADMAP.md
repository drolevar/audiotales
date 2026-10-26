# Implementation roadmap

Updated on 2026-10-26 after completing the native Figma design audit. This document describes sequencing and acceptance boundaries; Beads remains the task-status source. [Accepted decisions](DECISIONS.md) govern the product, and the [development guide](DEVELOPMENT.md#online-library-flow-verification--2026-10-25) records evidence.

## What exists

| Area | Implemented | Remaining qualification or features |
| --- | --- | --- |
| Libraries | Configurable HTTPS library registration, shared partner protocol, separate LibriVox.org API, grouped catalogue/search/pagination, source selection/management, book inspection and stable-ID saving; incoming shared book links and compatible legacy custom-scheme links | Public partner-access arrangement, broader provider compatibility and HTTPS domain association |
| Persistence | Room schemas 1–7; additive migration and historical-provider repair; atomic deduplication; stable track-source renewal; saved description/language; provider section ranges/shared audio identity; progress and bookmark storage | Bookmark UI, embedded chapters and source replacement semantics |
| Playback | One service-owned player; native Media3 controls; track selection, seeking and speed; periodic/transition progress; explicit source Retry; service-owned sleep timer and complete saved-book removal | Audio focus/headsets, physical-device and broader controller integration coverage |
| Downloads | Media3 download service/index/cache; real state/progress; pause/resume/retry/removal; complete-track-set availability | Broader storage-pressure, reboot, scheduler and network-transition coverage |
| UI | Adaptive My books/Browse/Settings; Material controls, lifecycle-aware state, theme persistence; fitted covers; saved-collection controls, Downloads overview, Browse/library management and full book details; focused player and native tool sheets; six-screen design audit complete | Broader assistive-technology and physical-device coverage |
| Verification | Parser/network/resolver/download-state unit tests; Android migration/storage tests; API 35 online and offline restart/resume checks; bounded API 26/37.2 UI and player tests | Android Auto/DHU, Bluetooth, broader playback lifecycle and release/R8 validation |

The earlier fixed-host HTML prototype has been superseded. Only provider request/response facts were reused from the legacy investigation; native app behavior was chosen independently. LibriVox.org and the shared partner protocol have separate contracts. Full offline availability refers to every track the provider returned, not a guarantee that the recording contains the complete published book.

Book and track identities are distinct and remain stable when source URLs change or downloads are removed. External playback requests resolve through application-owned repository IDs. No caller-provided URL or file path authorizes arbitrary media access. Tracks are not a substitute for an embedded chapter model.

The milestones below retain the broader acceptance criteria for future qualification; successful API 35 checks do not complete every platform scenario. The project does not claim verified Android Auto, audio-focus, Bluetooth, comprehensive platform behavior or reporting support. The pinned toolchain also retains the compatibility qualification recorded in the development guide.

Figma defines the implemented content hierarchy and screen capabilities, subject to native Android behavior and the approved My books/Browse/Settings navigation. My books is the saved collection; Browse owns discovery and search. Collection filters/sorting/progress and a Downloads overview are implemented. Grouped Browse and library management use durable source selection and preserved saved-book identity. Full book details retain real descriptions offline and expose track selection and saving/listening/download actions. The focused player, service-owned sleep timer, native tools and mini-player timing are implemented. The [completed design audit](UI-DESIGN-ACCEPTANCE.md) maps all six screens and supporting states to evidence; the broader product qualification below remains separate.

## Proposed milestones

The order below was revised on 2026-10-25 after the user clarified that online-library listening and offline downloads are the main use case ([decision 14](DECISIONS.md#14-online-libraries-and-offline-listening-first--accepted-2026-10-25)). This replaces the initial local-import-first sequence. Local import does not gate provider playback or downloads. Implementation tasks and their status remain in Beads.

### 0. Establish runtime evidence

**Dependencies:** Passing baseline; an API 26 device/emulator and a current-platform device/emulator; Android Auto host/DHU for the connectivity spike.

**Acceptance:** Launch the existing shell; navigate/back; change theme; rotate/recreate the Activity; observe controller connection and release; connect a supported car host and browse an empty root. Distinguish legacy service binding from authenticated controller connection. Record failures without weakening the trust gate to make a test pass.

**Tests:** Minimal shell UI/recreation checks; service/controller integration tests for connect, stop during asynchronous connect, disconnect, and separate Activity owners; empty root and unknown-ID results; reject untrusted controllers without exposing library metadata. Manual API 26/current and Auto connection evidence remains separate from automated tests.

**Risks:** Host trust differs across OS/Auto versions; service behavior cannot be inferred from a mock. This can run alongside milestone 1 but gates claims of working playback/car support.

### 1. Save an online-provider book from Browse

**Dependencies:** Verify the shared library protocol and current access requirements, using the user's examples. Consult the supplied legacy APK only for provider calls and parsing. Keep configurable library base URLs separate from protocol implementations; a domain need not require its own adapter. LibriVox.org's documented API is a separate protocol reference. Add the necessary network client/permission and extend Room through migrations preserving existing data. The former two-host HTML prototype has been replaced.

**Acceptance:** Browse or search a registered library, select a book, resolve its title/author and ordered tracks, and atomically save it in My books with stable app and provider identifiers. Reopening the same provider book does not duplicate it or reset progress. Preserve a canonical provider reference separately from resolved media locations. Display only the metadata and track set actually returned; a short recording is not proof that the whole book is available. Show explicit unsupported-library, unavailable-book, network and parsing outcomes. Notify connected library browsers when a book is added.

**Tests:** Sanitized provider fixtures for metadata, ordered/empty/malformed playlists, non-ASCII titles and network failures; link validation and deduplication; Room atomicity and v1 migration preserving progress/bookmarks; real provider lookup and saved-library restart checks. Keep live provider checks separate from deterministic tests.

**Risks:** Undocumented or changed endpoints, partner-client parameters, expiring CDN URLs, redirects, inconsistent metadata and provider outages. Do not commit the private compatibility client query or publish it as a production credential. Do not execute provider JavaScript in the native app or treat arbitrary shared media URLs as playback authority. Catalogue browsing/search is the listener-facing entry point; book URL parsing remains an internal integration capability.

### 2. Stream the provider book with durable resume

**Dependencies:** Milestone 1 and runtime service/controller evidence from milestone 0.

**Acceptance:** Select a saved provider book and send its stable track ID through the existing controller. Resolve or refresh the provider source internally, keeping transport URLs out of external controller authority. Show authoritative session title/state/position/duration with Media3 controls; support play/pause, seek, skip intervals and speed. Persist track/position periodically and at meaningful transitions, and restore after service/process recreation without unexpectedly starting audio. Define and verify a bounded resume-loss tolerance (proposed maximum five seconds during active playback). Surface connection, source-expiry, decoding and network errors with recovery.

**Tests:** Request-to-session integration, unknown IDs and supplied-URI rejection at the actual callback; progress interval/seek/pause/end transitions with a test clock; controller reconnect; real background/screen-off playback, rotation, notification/lock-screen controls, audio focus, headset/Bluetooth interruption and process death. Verify foreground-service behavior on current Android.

**Risks:** Saving only in `onDestroy` loses progress; frequent writes waste resources; restored state may race a new user request. There must still be one service-owned player. Sleep timer completion and richer phone controls can follow within this milestone once playback/resume is verified.

### 3. Download provider books for offline listening

**Dependencies:** Verified provider/source identity and streaming/resume; a supported source format; persistent app-managed download records and an Android-appropriate execution strategy. Evaluate Media3 DownloadManager/DownloadService and a shared download cache first, following [the official downloading guide](https://developer.android.com/media/media3/exoplayer/downloading-media).

**Acceptance:** Download a book's required tracks, show real queued/running/failed/completed state, and offer cancellation/retry/removal. A book is marked available offline only when its required track set is complete. Play and resume in airplane mode after app restart through the same service-owned player, without needing a provider refresh. Removing a download retains the book, bookmarks and progress and never deletes user-owned files. Streaming-to-offline transitions keep the same book and track identity.

**Tests:** Interrupted downloads, expired sources, retry/cancellation, storage exhaustion, partial track sets, process/reboot recovery, removal/history retention, and real offline playback. Verify foreground/background download behavior on API 26 and current Android. A successful HTTP request or incidental playback cache is not offline acceptance.

**Risks:** CDN source changes, cache identity, download integrity, large-book storage budgets and current Android background-work constraints. Keep one download cache owner and one player owner. WorkManager is not an assumed substitute for every user-initiated long download.

### 4. Complete chapters, bookmarks and Android Auto acceptance

**Dependencies:** Early connection spike, stable library IDs/queues and durable playback. Start browse/control checks during milestones 1–3; this milestone completes acceptance.

**Acceptance:** Model chapters separately from physical provider tracks; preserve bookmark track/position and cross-track resume. Browse books/tracks in Android Auto, start and resume a book, use supported media commands, update metadata/progress, reconnect and handle unavailable media consistently with the phone. Define and implement voice-search resolution before advertising its intent and removing the scoped lint exception. Record DHU and actual supported device/car results separately.

**Tests:** Chapter mapping, cross-track seeks and bookmark constraints; MediaBrowser/session callback integration, pagination boundaries, concurrent phone/system/car controllers, trust rejection, subscriptions/refresh, disconnect/reconnect and process recovery; DHU tests followed by vehicle/device testing when available.

**Risks:** Host-specific trust and command behavior, legacy clients, car UX requirements and unavailable network/provider content. Declarations alone are not acceptance.

### 5. Add local M4B and multi-file MP3 import

**On hold — 2026-10-26:** The user blocked M4B import and has not committed to implementing it. Do not schedule this milestone until the scope is explicitly reopened. The conditional design below is retained for reference.

**Dependencies:** Reliable playback/source identity and retained schema history; Storage Access Framework selection and an app-owned import operation. Consult [official SAF guidance](https://developer.android.com/training/data-storage/shared/documents-files) for persistable grants and moved/deleted-document behavior.

**Acceptance:** Start with one local M4B: retain the offered read grant, validate off the main thread, use a filename fallback when tags are absent, and atomically save stable book/track identities. Cancellation, unsupported/unreadable documents, duplicate import and missing persistent grants have explicit outcomes. Then add MP3 folder grouping with deterministic order and reviewable metadata. Re-selecting a moved source preserves book identity and listening history. Keep these user-owned files distinct from app-managed downloads.

**Tests:** Import cancellation/duplicate/grant failures, Room atomicity/foreign keys, natural ordering and partial folder failures; real SAF selection, restart/reboot access and permission-revocation recovery on API 26/current; migration from populated v1 preserving progress/bookmarks.

**Risks:** MIME inconsistencies, metadata parsing cost, provider I/O, grant acquisition versus transaction cleanup, and file relocation. Preserve source files and listening history. This capability is secondary to online/offline provider listening, not its prerequisite.

### 6. Structured diagnostics and automatic issue submission

**Dependencies:** Add bounded local diagnostic events alongside real import/playback failures earlier; full submission requires a backend, private storage, retention/abuse policy and a server-side GitHub App decision. No infrastructure is provisioned by this roadmap.

**Acceptance:** A listener describes a problem, reviews redacted diagnostics and taps Send. A durable local outbox retries offline; backend acceptance produces a receipt usable without repository access. GitHub issue creation is a separate state with reconciliation after uncertain outcomes. Detailed bundles stay private; issue text contains only sanitized summaries and opaque identifiers.

**Tests:** Redaction with credentials/signed URLs/library paths in fixtures; offline/reboot retry; duplicate delivery and ambiguous issue creation; rate limits, report-scoped authorization and retention; backend integration with a controlled test repository when authorized.

**Risks:** Embedding server credentials, leaking book/file details, duplicate issues and unauthenticated receipts. Automatic crash uploads require separate opt-in. Preserve release identifiers and R8 mappings when release builds begin.

## Delivery workflow

For each slice, read the accepted decisions, inspect existing changes, define observable acceptance, implement focused tests and run the build/lint/unit baseline. Add real device checks when the behavior depends on Android. Record exact evidence and remaining gaps in the development guide. UI implementation uses the installed Figma workflow and current Android guidance. Commit application work only in this repository; publishing and infrastructure provisioning require separate authorization.
