# Vutron Music - Mobile privacy information

Effective for public mobile release **1.0.0**; reviewed **2026-10-25**. Maintainer and source: **EBG-bg and contributors**, `https://github.com/EBG-bg/VutronMusic`. Report privacy issues through the repository's issue mechanism; do not post account cookies, signed media URLs, local music or device identifiers in a public issue.

## What the app handles

- **Account session:** QR login or a cookie you explicitly enter creates a NetEase session. `SessionStore` encrypts its stored cookie with AES-GCM using an Android Keystore key. The cookie must be decrypted in process memory when the app sends authenticated requests to the service. This is local storage protection, not a promise that a compromised device cannot read your session. The public APK and source must not contain the developer's or another user's cookies.
- **Profile and library:** account ID, nickname, avatar reference, playlists, track metadata, lyric cache, artwork and playback history/progress are stored in the app's private files/preferences. Playlist and artwork snapshots permit offline browsing. Audio files are cached after playback when automatic caching is enabled, or when you explicitly request saving a track, subject to capacity and availability.
- **Local music:** the Android system picker grants access to folders you select. Persisted folder URIs and an index of tags/durations are retained so the app can rescan and play them. A computer playlist ZIP you select is extracted into the app's private `local-bundles` directory. The importer reads its manifest/audio paths; it does not need your desktop account configuration.
- **Settings:** per-song/per-playlist volume, lyric delay, overlay colors and position, UI preferences, timers and the playback queue are saved locally.
- **Diagnostics:** native Android logging and the embedded matcher retain operational diagnostic events/errors on the device. The matcher diagnostic wrapper intentionally records fixed stage/source/error-type fields rather than account cookies or signed audio URLs. This is not a guarantee about Android system logs or third-party service logs. Review any exported diagnostic file before sharing it.

## Network destinations and disclosure

The app connects directly to NetEase endpoints for login, account and playlist requests, search, metadata, lyrics and playback addresses. Your login session accompanies the authenticated requests that require it. Artwork and audio are downloaded from their selected service/CDN URLs. Those providers receive normal network information such as IP address, requested resource, headers and request time, and apply their own privacy policies and retention rules.

When alternative-source matching is enabled, the embedded JavaScript matcher may contact the configured providers **bodian, kuwo, kugou, qq, bilibili, pyncmd and migu**, including the metadata endpoints they use. Queries can disclose a song ID, title, artist/album/duration and network information to those services. The matcher wrapper does **not** accept the app's NetEase account cookie. It is not a remote developer-hosted matching service. The embedded bridge binds only to `127.0.0.1` with a random per-process token. The active provider list can change with source updates; the shipped `server.js` is authoritative.

No developer-operated account, advertising, analytics or telemetry upload endpoint was found in the reviewed Android adapter/mobile frontend. This describes this release's implementation; it does not claim that Android, its WebView, your device vendor, network operator or the music providers perform no telemetry. The app does not include a maintainer analytics server.

The manifest permits network access. Loopback bridge traffic and some inherited provider/CDN routes can use HTTP; do not assume every media-provider route is end-to-end HTTPS. Platform transport policy and the release manifest are authoritative.

## Permissions

- **Internet / network state:** direct service calls, media/artwork downloads and Wi-Fi-aware lyric validation.
- **Foreground playback / wake lock / notifications:** background music, playback controls and the timer/service status.
- **Draw over other apps:** only for the desktop lyric overlay you enable. Locked overlays may be limited by Android's touch-obscuring/security rules; sensitive windows can hide them.
- **System folder picker grants:** only folders/documents selected through Android's picker; no need to provide an entire device export.

The app does not ask for microphone, camera, contacts or location access for these features. QR codes are generated for display; scanning takes place in your chosen service client.

## Your controls and deletion

- Sign out to remove the encrypted NetEase session and app profile state used for login. Signing out is **not** a promise to erase every cached media file, imported bundle, local folder grant or preference.
- Disable automatic audio caching or adjust its capacity in settings. An explicit manual cache request can still run while automatic caching is off. The cache clear action removes app-managed audio and playlist/artwork caches; it does not delete your original selected music folder.
- Disable/temporarily hide the lyric overlay or revoke Android's overlay permission. Revoke folder access through Android's app/storage controls as supported by your device.
- Android's **Clear storage** or uninstall removes the app's private session/cache/imported files and settings. Original files in user-selected folders belong to you and are not removed by the importer. OEM backups and restoration follow the final manifest and Android/device behavior; do not rely on this document as a substitute for checking the installation's backup settings.

There is no maintainer-side account database to request deletion from. Information held independently by NetEase or other providers must be managed through the relevant provider. When asking for support, share only the minimum metadata needed to reproduce an issue.
