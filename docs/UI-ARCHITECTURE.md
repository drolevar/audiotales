# Native Android interface proposal

Prepared on 2026-10-25 from the current source, accepted decisions, the two Figma references and current official Android documentation.

**Status:** the native stack, native behavior over exact visual fidelity, and use of Google's existing controls are accepted in [decision 12](DECISIONS.md#12-ui-design-and-implementation-references--accepted-2026-10-25). The native theme, My books collection controls, secondary Downloads overview, grouped Browse and library management were implemented on 2026-10-25, as described below. Other screen behavior and delivery recommendations remain proposed. This document describes UI architecture; implementation tasks belong in Beads.

## Design principle

Build an Android audiobook interface using Google's components, with AudioTales' visual character. Figma supplies the content hierarchy, spacious book lists, fitted artwork and restrained emphasis. Native components supply interaction, semantics, sizing, focus, motion and system integration. A difference from the screenshot is acceptable when it follows from that division.

Use an existing Google control when it supports the required behavior. Configure its supported slots, theme and parameters before considering custom UI. Compose existing controls into audiobook-specific arrangements. Only implement a missing behavior ourselves; document the gap. Do not rebuild a slider, search field, selection control or player button just to reproduce a Paper shape or icon.

Use the user-supplied [official Material 3 Design Kit](https://www.figma.com/community/file/1035203688168086460/material-3-design-kit) for component variants and design tokens, and the AudioTales boards for application composition. Current Android guidance and the pinned runtime controls determine actual behavior and API availability. A component appearing in the kit is not a reason to recreate it or add a preview dependency when the stable native component differs. The two AudioTales nodes were inspected through Figma MCP; the Community kit is recorded here as a supplied reference, not a separately completed component audit.

Keep Kotlin, Compose, stable Material 3, Navigation 3, ViewModel/StateFlow, Hilt, Room 3, DataStore and the single Media3 service. Keep one app module with clear packages. The accepted Coil, Retrofit and OkHttp integrations remain suitable for their agreed responsibilities; this UI policy is not a reason to replace them or introduce new abstractions.

## Screens and components

| Figma screen | Native implementation | Application responsibility |
| --- | --- | --- |
| My books | Material app bar, filter chips, lazy list, `ListItem` where its slots fit, standard icon buttons and overflow menu | Library query/filter/sort, progress and availability; stable book IDs |
| Search | Material search UI, standard text input/IME behavior, filter chips, selection sheet and result list | Provider search, cancellation, paging where needed, independent provider errors |
| Downloads | Lazy list of Material rows, progress indicators, supported action buttons and confirmation dialog | Observe the real download manager; distinguish queued, active, paused, failed and complete |
| Manage libraries | Material app bar and labeled selection rows, with a separate browse/details action | Persist enabled sources and expose only supported provider configuration |
| Book details | Adaptive content layout using Material text, buttons and lists; fitted artwork | Metadata, availability, Listen/Resume, chapters and download actions |
| Now playing | Media3 Compose Material controls arranged around artwork and audiobook metadata | Chapter semantics, bookmark actions, sleep timer and integration with the existing session |

Extract a few reusable pieces: fitted cover artwork, book metadata, availability status, and book rows composed with different trailing actions. Prefer slots and small components over one row controlled by many booleans. Reuse Material typography and color roles rather than scattering Figma pixel values across screens.

The references are [the main board](https://www.figma.com/design/LGhPAOlgrNdvcsUtrdBvqN/Audiotales--Paper-v5?node-id=27-404) and [reusable parts](https://www.figma.com/design/LGhPAOlgrNdvcsUtrdBvqN/Audiotales--Paper-v5?node-id=56-945). Their sample books and providers do not select production content or integrations.

## Navigation and window adaptation

The accepted top-level destinations are **My books, Browse and Settings**, retaining the current navigation bar and icons ([decision 15](DECISIONS.md#15-saved-collection-catalogue-discovery-and-visual-completion--accepted-2026-10-25)). My books contains saved books; Browse owns catalogue browsing/search. This supersedes the earlier proposal to copy the mockup’s My books/Search/Downloads destinations. A downloads overview can use a secondary route. Use `NavigationSuiteScaffold` to select navigation bar or rail from the current window information. Let its defaults account for height and posture as well as width; do not classify the device once as phone or tablet. Google's adaptive navigation documentation describes this behavior. [Source](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)

Use Navigation 3 serializable route keys, saved back stacks per top-level destination, and saveable-state/ViewModel entry decorators. Save the selected destination too. Switching destinations preserves each stack, search input, filter selection and list position. Restore compact state and reload repository data after process death; do not put results, controllers, artwork or media URLs into saved navigation state. Google's multiple-stack recipe supplies the starting pattern. [Source](https://developer.android.com/guide/navigation/navigation-3/recipes/multiple-backstacks)

Proposed navigation rules:

- Switching a top-level destination restores its existing stack. Reselecting the current destination initially does nothing; do not add implicit refresh or scroll reset.
- Back first dismisses the keyboard or active modal, then pops a detail destination. At a Browse or Settings root, Back returns to the My books root while retaining the other stacks; at the My books root it leaves the app. This exit-through-home policy is a product choice, consistent with Google's sample, not an Android requirement.
- Now playing is one app-level destination above the browsing shell. It always represents the session's current book. Opening or closing it never starts, pauses or switches playback. Returning restores the exact browsing origin; a notification launch without an origin returns to My books.
- Catalogue rows lead to Book details. Saved rows retain the Figma shortcut to the player surface without starting or switching playback; Listen/Resume explicitly selects the book. Controls bind only to the active session book. The mini-player opens the active player directly. A saved-row details menu entry belongs to the full-details implementation.
- Show the mini-player whenever the session has a current book, including while paused, across browsing and detail screens. Suppress it in Now playing itself. A focused search surface or modal can cover it; playback continues. Keep primary navigation at top-level screens on compact windows; secondary screens have standard Up navigation.
- Use Navigation 3 and Material transitions/back handling, including predictive back where supported. Do not add a competing global back handler or custom edge-swipe recognizer.

On a larger window, Search results and Book details can become a list/detail composition. Use Material Adaptive's Navigation 3 scene strategies to render the same route entries in panes, rather than building a second navigation system. The stable Adaptive 1.3.0 release includes `adaptive-navigation3` and list/detail scene strategies. Confirm dependency resolution against the project's existing pins when adding it. [Source](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)

My books can use the same list/detail arrangement when there is a selection. Resizing must preserve selection and playback. The focused player should use a bounded artwork column beside controls when space permits; on narrow or short windows, reduce the artwork area and allow content to scroll rather than shrinking touch targets. Keep artwork proportional and uncropped.

## Playback: Google controls, one session

Add `androidx.media3:media3-ui-compose-material3` at the existing Media3 version when implementing playback UI. It already depends on `media3-ui-compose`; a second direct dependency is unnecessary. Use its play/pause and seek controls, and its `MiniController` for compact playback. Compose these into the audiobook layout, retaining their native behavior. The official guide also documents a complete `Player` composable; use it if its supported configuration fits the audiobook layout, without adopting video-specific presentation or unrelated transport actions. [Source](https://developer.android.com/media/media3/ui/compose)

Feed these controls the existing **MediaController**, which implements the Player interface. Do not create an ExoPlayer in a composable or use a demo's player-creation code. The service remains the sole player owner. Closing the Activity releases its controller connection, not the service's ongoing playback. [Source](https://developer.android.com/media/media3/session/connect-to-media-app)

The current `PlaybackConnection` is Activity-scoped. Keep it at the Activity/Compose boundary and make a connected controller available to the player UI there. Do not inject it into retained Hilt ViewModels. Library and detail ViewModels continue to depend on repositories. Simple transport controls use the controller; service-side domain commands handle operations that require book/chapter resolution.

Distinguish built-in controls from audiobook-specific behavior:

| Concern | Decision |
| --- | --- |
| Play/pause, 15-second rewind, 30-second forward | Use Media3's controls and actual command availability; use the service's configured seek increments |
| Mini-player | Use Media3 `MiniController` with supported content/theme customization; only adapt missing audiobook metadata or fitted-artwork presentation |
| Previous/next chapter | Resolve a chapter to a track and offset in application data; do not use track-skip controls unless tracks and chapters actually coincide |
| Seek position and duration | Use the available Media3 control/state support and a Material slider for any missing chapter-relative presentation; preserve native slider semantics |
| Speed | Read and change session playback parameters; present choices with a standard Material menu or sheet |
| Sleep timer | Standard Material choices; the countdown and stop behavior belong to the service, so leaving the screen does not cancel it |
| Bookmarks | Persist book ID, track ID and offset through the repository; show a standard action and feedback |

For chapters embedded inside M4B tracks, the displayed chapter time is the track position minus the chapter start. Seeking maps it back to the correct track offset. Keep book, track and chapter identities distinct. Unknown duration or unsupported seek commands disable seeking rather than inventing a range. A user's drag position is temporary UI state; commit the seek when the gesture finishes and do not overwrite it with incoming ticks during the drag.

Use Media3's state holders for missing presentation needs before writing new Player listeners. Keep high-frequency progress updates local to the player controls, not the entire catalogue list. The service owns durable resume writes at meaningful transitions and a bounded periodic cadence; the screen's timer never owns persistence. Controller reconnection adopts the current session state, without resetting playback from an older UI snapshot.

During connection loss, retain identifiable last-known metadata with a clear reconnecting/error state and disable commands until available. If a restored Now playing route has no active session item, show an honest empty state with a return action. Do not create an active book solely to make the player screen look populated.

## State, data and feature boundaries

Keep screen routes responsible for ViewModel lookup, lifecycle-aware collection and callback wiring. Keep content composables parameterized by state and actions so previews can exercise them without services or a database. Follow unidirectional data flow; ongoing repository work belongs outside composition. [Source](https://developer.android.com/topic/architecture/recommendations)

Room is authoritative for the personal library, durable progress, bookmarks and future persisted source/download records. The playback session is authoritative for current transport state. The download manager is authoritative for transfer progress. DataStore owns small preferences. A ViewModel combines these into a screen model; it does not create a second player or a simulated download lifecycle.

Represent listening state and source availability separately. A book can be partially listened to and missing local access, or finished and still downloaded. Removing an app-managed download retains the book and listening history. A permission failure on a user-owned file offers re-selection; it is not a failed managed download. Only delete app-owned downloaded files when executing Remove download.

Search results retain stable provider and remote book identifiers, not URLs as route or playback authority. Resolve and register a selected source through application repositories before requesting playback by stable library IDs. Preserve the accepted external-controller boundary. Online libraries and managed downloads are the primary product flow under decision 14; the first provider investigation is audioknigi.com.ua. The mockups do not establish provider compatibility or choose a transfer engine.

The user-supplied legacy APK may be consulted only for the selected online audiobook provider's requests, parsing and media-source resolution. It supplies no UI, state-management or player architecture for this proposal.

## Deliberate adaptations from Figma

These are proposed product choices where noted, rather than claims that Google mandates one exact audiobook design.

| Area | Recommendation and reason |
| --- | --- |
| Component appearance | Accept native Material/Media3 dimensions, shapes, pressed states and motion. Theme them; do not rebuild controls to match the screenshot |
| Library selection | Make the labeled row toggle inclusion, with a separately labeled browse/details action. Do not make tapping the checkbox select while tapping its label unexpectedly browses |
| Book-row action | Catalogue results open details; saved rows open the player without autoplay. Listen/Resume explicitly starts or switches playback; mini-player returns to the active session |
| Insets and system UI | Draw edge-to-edge using actual system/IME insets. The mock status bar and gesture pill are illustrations, not application UI. Give each inset one layout owner to avoid double padding |
| Large text and input | Let rows and action groups grow or reflow; provide at least 48 dp targets and non-overlapping actions. Check TalkBack, keyboard focus and switch access rather than relying on visual similarity |
| Theme | Recommend device dynamic colors on Android 12+, with a complete Figma-derived purple light/dark fallback and an option to use that palette. Preserve the existing System/Light/Dark preference. Dynamic color support is Google's capability; enabling it by default is this proposal's product recommendation |
| Missing entry points | Keep Settings/About reachable. Save online books through Browse; do not add a book-URL action or move discovery into My books. Add local Import audiobook when SAF import works; neither action should appear before its capability exists |
| Animation | Start with component and navigation defaults. Additional cover transitions are polish, not a prerequisite, and must not delay a playback command |

Material components provide useful default accessibility semantics, but composite rows still need correct action labels and focus behavior. Avoid redundant artwork announcements when adjacent text already identifies a book. Announce play/pause state and chapter seek units correctly; do not announce every progress tick. [Source](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

The inset and color recommendations follow [Android's inset model](https://developer.android.com/develop/ui/compose/system/insets) and [Material 3 theming](https://developer.android.com/develop/ui/compose/designsystems/material3). Neither fixed 393 dp layouts nor suppression of font scaling is an acceptable way to match the board.

## Delivery and verification

The first UI implementation established the native theme, reusable book presentation, and honest My books loading/empty/error/list states while preserving Settings/About. It uses native Material app bars, icon buttons, list items, radio buttons and a switch. Dynamic colors default on for Android 12+, with a persistent option to use the purple palette. The existing System/Light/Dark choice remains independent. Saved rows now open the working player route; catalogue rows open metadata before saving. Fitted provider covers are shared across both lists, catalogue details, player and mini-player. Saved rows now include real listening/download state, filters and sorting. The secondary Downloads overview supports details, pause/resume/retry and confirmed removal. Full book details and library management remain future slices.

Library and Settings previews provide explicit fixtures, including populated rows with long titles and missing authors. They are visual development tools, not data displayed in the released application. Future player and download previews should follow the same rule.

Follow the revised [roadmap](ROADMAP.md): implement a small real online-provider book flow, then service-backed streaming/resume with Media3 controls and managed offline downloads. Continue car validation alongside playback; add chapters/bookmarks and local SAF import in subsequent slices. Browse now provides discovery/search. Keep the approved My books/Browse/Settings destinations and icons as remaining design features arrive; empty lists do not remove destinations.

Adopt adaptive navigation when multiple functional destinations exist and list/detail scenes when detail browsing is implemented. Support resizing, large text and safe insets from the first screen. Introduce only the dependencies needed by that slice. Stable published dependencies can still contain experimental APIs; inspect annotations and justify any scoped opt-in, rather than silently adopting a preview dependency or upgrading the entire toolchain.

Verification must cover behavior as well as rendering:

- Preview and inspect narrow, reference-width, expanded and short landscape windows, light/dark themes, large fonts, long titles and mixed scripts. Review against the Figma's hierarchy, not pixel equality.
- Test saved tab stacks, selected detail, search input and list positions through recreation and restored state. Check Back and predictive-back cancellation, including the full player returning to its origin without stopping playback.
- Test meaningful user actions and disabled-command states. Verify chapter-to-track seek mapping, removal of managed downloads without deleting library history, and access recovery as those features arrive.
- Validate TalkBack traversal, readable contrast, touch-target separation and slider semantics on device. Automated screenshots alone cannot establish these behaviors.
- Run the project's build/lint/unit checks for substantive implementation changes. Then test real playback on API 26 and a current Android version, including backgrounding, controller reconnect, audio focus, headset/Bluetooth changes and durable resume. Validate Android Auto separately through DHU/device workflows.

The proposal itself did not implement features or validate devices. Subsequent UI foundation work passed build/lint/unit checks and bounded API 35 emulator navigation, appearance persistence and large-text/landscape rendering checks; see the [verification record](DEVELOPMENT.md#native-ui-foundation-verification--2026-10-25). Playback, Android Auto and the remaining proposed screens are not validated by those results.
