# AudioTales

An audiobook player for Android, built for discovering books in online libraries and enjoying them online or offline—at home, on a walk, or in the car.

**AudioTales is in early development.** Preview APKs are available from
[GitHub Releases](https://github.com/drolevar/audiotales/releases/latest) for
people with access to this repository. Install the signed APK to update an
existing release installation while keeping your books and listening progress.

## Available in preview builds

- Add compatible online libraries by their website addresses, choose which to search, browse results by library, and save books to My books.
- Browse a library separately, rename it, share its address, or remove it from Browse while keeping your saved books and downloads.
- See book covers in the catalogue, your saved collection and the player.
- Read book descriptions and inspect the track list before saving. Saved descriptions and track lists remain available offline.
- See LibriVox reader credits separately from authors, including saved credits offline. Use **Refresh details** to update older saved books.
- Share a supported book link from your browser to AudioTales to open its details. Opening a link does not save the book or start playback; a new library requires your confirmation.
- Filter saved books by listening or download state, and sort by recent listening, title or author.
- Listen with play/pause, seeking, speed and track controls, and resume your saved position.
- Set a sleep timer for a chosen duration or the end of the current track.
- Download a book for offline listening. Manage downloads from My books or Settings, pause or resume transfers, and remove downloaded audio while keeping the book and listening position.
- Browse My books and search your enabled online libraries through Android Auto. Car playback and phone media notifications show the same book cover and author as the phone player.

Library support depends on the website's available service and the build's configuration; adding an address does not make every website compatible. A book is shown as available offline only after all tracks supplied by that library are saved.

AudioTales also accepts compatible `audiotales://` book links. Some websites send their links specifically to the old AudioTales app; use your browser's Share action with the original book link in that case.

## Planned features

- **Embedded chapters and bookmarks.** Navigate chapters within recordings and save moments you want to revisit. Track navigation is already available.
- **Android Auto voice search.** Find and start a book by voice.

AudioTales requires **Android 8.0 and later**. Device testing is still in
progress. Android Auto has its own phone and vehicle requirements. Its
browsing, typed search and playback controls have passed
sampled tests with Google's Desktop Head Unit. Broader car compatibility,
rotary controls and physical headset/Bluetooth testing remain in progress.

## Reporting a problem

We’re planning a simple way to report problems from inside the app, without needing a GitHub account. You’ll be able to describe what happened, review the diagnostic information to include, and send your report.

This feature is still in development.

## License

AudioTales is licensed under the [MIT License](LICENSE).
