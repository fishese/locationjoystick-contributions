# What's New Popup

Small badge, shown app-wide, that tells the user this version has changes worth a look. Not
mandatory — the badge can be dismissed without opening it, and it never blocks the app.

Key files: `:app/WhatsNewPopup.kt`, `:app/WhatsNewViewModel.kt`, `:core:data/WhatsNewRepository.kt`

## Behaviour

- A pill badge ("What's new") appears bottom-start on the home screen (`LjApp.kt`'s root `Box`,
  same overlay layer as the global snackbar) whenever `AppConstants.AppInfo.VERSION_NAME`
  differs from the last version the user acknowledged. Other screens do not show it. This check is local (DataStore vs. a
  compile-time constant) — the badge itself needs no network and appears offline.
- **Tap the badge**: marks the current version seen, opens a modal, and reads
  `AppConstants.WhatsNewConstants.assetFileName(VERSION_NAME)` from the APK assets
  (`WhatsNewRepository`) — the same per-version JSON file the wiki changelog is built from
  (see "Single Source of Truth" below). Shows a loading spinner while the file is read, the
  bullets on success, or a short inline message on failure ("Couldn't load what's new...")
  with the "View full changelog" button still available as a fallback.
- **Tap the badge's close (×)**: marks the version seen without opening the modal or reading
  assets — reviewing the changelog is never required.
- Once marked seen, the badge stays hidden until the next version bump.
- "View full changelog" opens `AppConstants.AppInfo.CHANGELOG_URL`
  (the public user guide's changelog).

## Single Source of Truth

The app does **not** carry a hardcoded Kotlin list of highlights. Both the popup and the wiki
are built from the same per-version file:

```
docs/wiki/changelog/<version>.json    →  { "version": "...", "highlights": ["...", ...] }
```

`:core:data` packs that directory as Android assets (`assets.srcDir` in `core/data/build.gradle.kts`).
`WhatsNewRepository.fetchHighlights(version)` opens `assetFileName(version)` from `AssetManager` —
never a network URL, so highlights remain available offline.

- **Missing / unreadable file**: `fetchHighlights` returns `null`, the dialog shows a short error
  with the changelog link as a fallback. The badge itself is unaffected.
- **Offline**: the popup works with no network. That matches the app's offline-first stance.

## Storage

`AppSettings.whatsNewLastSeenVersion` is **not** part of `AppSettings`/`ExportData` — it's a
per-device UI acknowledgment, not app data, matching `ThemeMode` and
`REMEMBER_LAST_LOCATION` (see @docs/features/theme.md). Persisted via
`SettingsRepository.getWhatsNewLastSeenVersion()`/`setWhatsNewLastSeenVersion()`,
DataStore key `whats_new_last_seen_version`, default `""` (never seen).

## Maintaining the Highlights

Any release with user-visible changes needs **both** of these, written together in the same
commit — neither is optional, and this file is the only place both steps are spelled out:

1. **`docs/wiki/changelog/<version>.json`** — the machine-readable file packed into the APK.
   2-4 short, plain-English bullets under a `"highlights"` array (see
   `docs/wiki/changelog/0.18.2.json` for the shape). This is the *only* place the popup's
   content comes from — there is no app-side Kotlin list to also update.
2. **`docs/wiki/changelog.html`** — the human-readable, fuller changelog entry for the same
   version, in prose, following `docs/wiki/CONTRIBUTING.md`'s page structure and writing style.
   Keep `changelog.html` in sync with the packed popup highlights.

Write the JSON bullets as a short summary of the same release the `changelog.html` entry
describes in full — not a separate editorial pass. No app-side Kotlin change is needed to
publish a new version's highlights: bump `AppConstants.AppInfo.VERSION_NAME` and add the
matching JSON file; the next release's APK picks it up from assets.
