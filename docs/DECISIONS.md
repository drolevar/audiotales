# Decisions and scope

Recorded on 2026-10-24 before Android scaffolding.

This is the durable record of the choices made for AudioTales. **Accepted** means the product or technical direction has been agreed. **Implementation choice** records a reversible choice made for the initial scaffold. **Proposed** and **deferred** items are not commitments or implemented features.

## 1. Product and distribution — accepted

AudioTales is a native Android audiobook player, with Android Auto support as a first-class requirement. Its primary use case is finding or opening books from online audiobook libraries, listening online, and downloading them for offline listening. Local M4B and multi-file MP3 import remains a secondary supported direction.

The intended listening features are reliable resume, chapters, bookmarks, adjustable playback speed, a sleep timer, offline listening, and car controls. Online catalogue access and managed downloads are core product features. The user's audioknigi.com.ua example is the first integration to investigate; provider compatibility is not established merely by selecting it. See decision 14 for the 2026-10-25 priority clarification, which supersedes the initial local-import-first delivery order.

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

Use the stable Room 3 line for this new project, with its `androidx.room3` APIs and KSP compiler. Keep the minimum SDK at 26 while compiling and targeting API 37. Pin and verify the complete toolchain together.

System backup is initially disabled until a restore strategy is defined for persisted file access, listening state, and report credentials. Cloud sync and account-based recovery are deferred.

The first scaffold should include:

- A launchable Compose app with an honest empty library and working navigation.
- Hilt wiring, Room library/progress/bookmark foundations, and a real DataStore preference.
- A single Media3 library service, Android Auto declarations, and controller lifecycle plumbing.
- A Gradle wrapper, a pinned version catalogue, release configuration, and a GitHub Actions build/lint/test workflow.
- Focused validation of meaningful state and boundary behavior, without pretending device/car tests have run.

The initial scaffold plan put local import first. Decision 14 supersedes that sequence: establish one online-provider flow, reliable streaming/resume and managed offline downloads before local import. A report UI and backend follow as actual features; do not expose nonfunctional Send or download controls.

## 11. Verification and change management — accepted direction

Build and lint the scaffold, run relevant automated tests, and record the exact results. Test on API 26 and a current Android version, then validate car behavior with Android Auto/DHU before claiming support is complete.

Keep build tooling requirements and commands in `docs/DEVELOPMENT.md`. Keep secrets, local SDK paths, generated build output, and signing keys out of version control. Release signing and publishing require their own setup.

For each material decision change, record its date, reason, consequences, and what it supersedes. Proposed services and future capabilities must stay distinguishable from code that is already implemented.

## 12. UI design and implementation references — accepted 2026-10-25

Follow the user-selected **Audiotales — Paper v5** Figma design book for the application's UI. These two nodes are the canonical design references:

| Reference | Role |
| --- | --- |
| [Audiotales · Current design · v0.7.0](https://www.figma.com/design/LGhPAOlgrNdvcsUtrdBvqN/Audiotales--Paper-v5?node-id=27-404) | Main screen compositions and supporting states: My books, Search, Downloads, Manage libraries, Book details, and Now playing |
| [Current UI / v0.7 reusable parts](https://www.figma.com/design/LGhPAOlgrNdvcsUtrdBvqN/Audiotales--Paper-v5?node-id=56-945) | Reusable icons, catalogue book row, and download row |

The user also supplied the [official Material 3 Design Kit](https://www.figma.com/community/file/1035203688168086460/material-3-design-kit) on 2026-10-25. Use it as the Material component and design-token reference alongside the AudioTales compositions. The kit does not establish API availability in the project's pinned stable Compose or Media3 dependencies; verify that separately against current Android documentation.

Access to the two AudioTales nodes, their names, structure and screenshots were verified through Figma MCP on 2026-10-25. The main board uses a 393 dp reference width and sample catalogue/playback data. Preserve the design's visual hierarchy, overall character and fitted, uncropped artwork while implementing with the accepted native Kotlin/Compose and stable Material 3 stack. The reference width is not a fixed device requirement. Retrieve current node-level design context and assets when implementing each screen.

Clarified by the user on 2026-10-25: **native Android behavior takes priority over 100% fidelity to Figma**. Prefer Google-native or Google-endorsed modern practices. Where Google provides a control that covers the required behavior, use it and customize through its supported theming and composition APIs instead of recreating it to match the mockup. In particular, use Media3's Compose controls for playback. Apply this rule to navigation, search, selection, sheets, dialogs and other comparable UI decisions. A custom control needs a concrete behavioral gap in the provided controls, not merely a visual difference. Keep the accepted stable dependency policy; this preference does not authorize preview upgrades or replace the previously accepted architecture.

Use platform behavior for back navigation, window insets, resizing, font scaling and accessibility. This clarification supersedes exact reproduction of Figma component treatment or interaction details where they conflict with native behavior. The [UI architecture proposal](UI-ARCHITECTURE.md) distinguishes these accepted principles from screen-level recommendations that remain proposed.

This establishes the visual target for future UI work and supersedes treating the initial scaffold's appearance as the final design. It does not change the accepted playback architecture, feature delivery scope or catalogue-provider selection. Represent only working functionality in the app; sample content and future controls in the design are not evidence of implemented features.

Clarified by the user on 2026-10-25: "other libraries" means **online audiobook libraries/catalogues and their providers**, not software libraries or framework dependencies. The other implementation uses React Native and Paper v5; consult it narrowly for how it communicates with a specific audiobook service, such as requests, response parsing, search, pagination, book/chapter metadata and media-source resolution. The user subsequently supplied its version 1.1.21 APK as the integration reference. Keep the artifact, extracted code and private investigation outside this application repository.

Inspect only the relevant provider integration code when that work is needed. Do not use the other app's UI, React Native/Paper components, state management, playback or overall architecture as implementation guidance. The Figma design book guides the UI, and AudioTales' accepted native Android decisions govern implementation. Provider code is evidence to verify against the service's current behavior, not proof that an old integration still works. This clarification supersedes the broader wording "library/provider integration details." No feature implementation or Figma edits were made when registering these references.

Further clarified on 2026-10-25: the full library/book-management surface may be investigated to locate integration paths, but **only the library API contract is reusable**: endpoint construction, parameters, response fields, identifiers, pagination and media resolution/headers. Legacy navigation, validation, saving, deletion, progress and download behavior are observations, not product requirements. Decide those behaviors independently from AudioTales' goals, the accepted native architecture, current Google guidance and the Figma composition references.

## 13. Native UI foundation — implementation choices, 2026-10-25

The first implementation slice applies the native-control policy to My books and Settings. Use Material 3's dynamic light/dark schemes by default on Android 12+, with a complete purple fallback derived from the Figma reference. A persisted **Use device colors** switch lets the listener choose the purple palette on supported devices. The existing System/Light/Dark setting remains independent. On earlier Android versions, use the purple palette and omit the unsupported device-color option. Enabling dynamic colors by default is a product choice, not a platform requirement.

Use Material list items for book metadata, with a decorative placeholder until real artwork is available. Keep loading, empty, error and populated states explicit. Previews may use fixtures, but the real library stays repository-backed and initially empty. Do not add import, book-detail, playback, Search or Downloads actions until they work. This replaces the scaffold's initial teal appearance without changing playback, storage, toolchain or provider decisions.

## 14. Online libraries and offline listening first — accepted 2026-10-25

The user clarified that the main use case is **listening to and downloading books from online audiobook libraries**, with [this audioknigi.com.ua book page](https://audioknigi.com.ua/audio-86717-follett-ken-igolnoe-ushko-1) as a concrete example. The primary journey is to find or open a provider book, add it to My books, listen online, and download it for offline listening. Local-file import remains useful but is not a prerequisite for the primary journey. This supersedes the initial roadmap's local-M4B-first ordering and its placement of remote catalogues/downloads near the end.

Start with one provider and a small, verifiable book-link-to-library flow, then real service-backed streaming with durable resume and managed offline downloads. Expand catalogue search/browsing and providers on that foundation. Preserve the Figma hierarchy and native-control policy; do not expose nonfunctional Search, Listen or Download actions while these are being implemented.

The user subsequently supplied three concrete provider examples for this flow:

- [Роберт Шеклі on librivox.club](https://librivox.club/audio/26923-robert-shekli-cherez-travnij-trakt-u-kosmos-za-dopomogoju-mantri-tantri-ta-strokatih-kolis).
- [Игольное ушко on audioknigi.com.ua](https://audioknigi.com.ua/audio-86717-follett-ken-igolnoe-ushko-1).
- [Following the Color Line on librivox.org](https://librivox.org/following-the-color-line-by-ray-stannard-baker/).

Treat these as separate providers with independently verified contracts. Similar names do not establish a shared API. Share parsing or transport code only where current evidence supports it, and preserve provider-scoped identities.

The user also clarified that the legacy app accepts a library base URL such as `https://audioknigi.com.ua` even without that domain being built in. Model configurable library instances separately from the protocols they implement; do not equate each domain with a required hardcoded adapter. Determine compatibility from an actual protocol contract. Support for arbitrary websites does not follow from accepting a URL. The three book links are integration examples, not a fixed provider allowlist.

Keep provider/book/track identity separate from resolved transport URLs and local download locations. Refreshing a provider source or removing an app-managed download must preserve book identity, progress and bookmarks. Streaming and offline playback use the same single service-owned ExoPlayer and stable library IDs. A provider URL supplied through sharing or a link is input to a supported provider adapter, never authority for an external controller to play an arbitrary location.

Evaluate Media3's DownloadManager, DownloadService and shared download cache first for the provider's actual media format. A separate download service is not a second player owner. Download state comes from the download subsystem, while Room retains library and listening identity. Full offline availability requires the complete required track set; a temporary playback cache or one downloaded track is not automatically a downloaded book.

## 15. Saved collection, catalogue discovery and visual completion — accepted 2026-10-25

After trying the first listening build, the user confirmed that it is an early functional slice and that the Figma design book remains the product's visual and functional target. Preserve decision 12: native Google controls and Android behavior take priority over exact reproduction of the React Native/Paper mockups. Missing artwork or unfinished screens are implementation gaps, not deliberate native adaptations.

Keep the current adaptive navigation bar, its icons, and the My books / Browse / Settings destinations. **My books is the saved collection; Browse is online-library browsing and search.** Select and save books from Browse. Remove the direct book-URL entry action and dialog from My books; do not repurpose the saved collection as catalogue discovery. Adding a library's base URL remains a separate configuration operation in Browse.

Display real provider covers in catalogue results, saved books and the player, fitted without cropping. Persist artwork identity and required request headers with the saved book, using additive migrations. Existing saved books should acquire missing artwork through metadata refresh without replacing their tracks or resetting progress/downloads. Missing or unavailable artwork must not prevent playback.

Continue closing the remaining Figma gaps in coherent feature slices, including richer details, collection filters/sorting, library management and download presentation. Preserve the approved navigation when placing these features. Treat proposed controls as unfinished until their behavior is implemented and tested; completion of the first listening flow is not completion of the whole application.

## 16. Collection and download surfaces — implementation choices, 2026-10-25

My books follows the Figma collection hierarchy with All, In progress and Downloaded filters, a count and a sort menu. Recent means most recently listened, using the saved progress timestamp; unplayed books have a deterministic title fallback until an explicit added-date model is needed. Show a whole-book played percentage only when the durations of every required track are known. Otherwise show the saved track and position. Do not substitute track count for elapsed listening time.

Downloads is a secondary destination reachable from My books and Settings, preserving the user-approved bottom navigation and its icons. It lists managed downloads and derives availability, progress, pause/resume/retry and removal state from Media3. Removal confirms that it deletes only downloaded audio, keeping the saved book and listening history. A book remains offline-ready only when its complete required track set is stored. Unknown byte lengths use an indeterminate indicator and real saved-track counts rather than a fabricated percentage. Keep the mini-player available on this screen.

Use Material FilterChip, menus, dialogs, progress indicators and icon buttons with Material Symbols. This adapts the Paper mockup to the accepted native-control policy. These changes do not redefine full design completion: Browse/library management, full book details and the remaining player tools still require their own implementation and verification.
