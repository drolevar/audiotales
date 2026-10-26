# Native UI design acceptance

Audit date: 2026-10-26. This record maps the implemented interface to the [AudioTales Figma design book](https://www.figma.com/design/LGhPAOlgrNdvcsUtrdBvqN/Audiotales--Paper-v5?node-id=27-404), including its supporting state/component board (`56:945`). [Accepted decisions](DECISIONS.md) take precedence over literal Paper styling; this is native Android design parity, not pixel equality or product-release qualification.

The six-screen design milestone is complete with the approved native adaptations below. The final audit added missing mini-player timing and verified it with six focused Android tests on each API 26 and API 37.2. The supporting accessibility and state checks completed on API 37.2 supplement the earlier screen-by-screen evidence.

## Screen and state mapping

| Figma frame | Native implementation and inspected behavior | Evidence |
| --- | --- | --- |
| My books `34:1126` | Saved collection, search shortcut, filters, count/sort, fitted covers, listening/download state, book actions and mini-player with track timing | Collection/accessibility audit and final mini-player audit |
| Search `57:955` | Browse search, selected libraries, Default order, grouped results, pagination and library management; real unmatched search and provider error/retry presentation | Browse inset/recovery and final state audits |
| Downloads `57:1084` | Secondary Downloads destination, actual transfer state/progress, pause/resume/retry, per-track details and audio-only removal; explicit empty/error/busy states | Seven controlled Downloads tests on each API 26/37.2 plus saved-fixture and TalkBack checks |
| Manage libraries `34:1324` | Selection, direct browsing, add/rename/share/removal, validation and retained saved-book identity | Constrained-window/platform audit; TalkBack chooser, checkbox and source-menu checks |
| Book details `57:1255` | Real identity, source/language, fitted cover, saved state, listen/save/download actions, track sheet and offline description; unavailable/retry rendering | Details/state tests, constrained-window audit and TalkBack track-sheet activation/focus return |
| Now playing `57:1331` | Fitted artwork and centered identity, availability, native progress/transport/speed, service-owned timer, tracks, downloads, information/share and complete book removal | 17 player/removal tests on each API 26/37.2; saved-fixture runtime and TalkBack timer/removal checks |

The supporting source chooser, download menus, queued/empty states, information/download sheets and removal dialogs are included in these checks. Verification details and limitations are recorded chronologically in [DEVELOPMENT.md](DEVELOPMENT.md#mini-player-timing-and-final-design-audit--2026-10-26).

## Approved native adaptations

- Retain the approved My books/Browse/Settings navigation and icons. Downloads and library management are secondary destinations. My books contains saved books; Browse owns discovery and search.
- Use stable Material components and Media3 controls. The service remains the sole playback owner. Preserve native focus, touch targets, system insets and Back behavior rather than Paper-specific dimensions.
- Use actual provider covers, names, counts and transport state. Fit covers without cropping; allow wrapping and scrolling in narrow, short or large-text windows. Dynamic colors remain optional and the purple palette is available.
- Call provider order “Default.” Display tracks honestly; a provider track title may contain the word “Chapter,” but the app does not imply an embedded-chapter model.
- Remove downloads deletes managed offline audio only. Remove book deletes the saved book, its tracks, downloads, progress and bookmarks. Separate confirmations explain the consequences; cancellation preserves data.

## Acceptance boundary

The audit covers the six designed screens, their implemented supporting states and representative native accessibility interactions. Earlier checks cover light/dark appearance, narrow portrait, short landscape, 200% text, API 26 and API 37.2, saved-state navigation and theme persistence. Screenshots are inspected evidence, not golden-image comparisons. TalkBack interaction ran on API 37.2; the API 26 image has no TalkBack service.

Provider availability and exhaustive transport recovery, physical-device audio, Android Auto, focus/headset/Bluetooth behavior, connected-controller races, release signing and R8 remain broader product qualification. One live partner book remained unavailable after retry during this audit; this does not establish that the retry path recovered. Bookmark editing and embedded chapters remain separate roadmap features. No release-readiness claim follows from this UI audit.
