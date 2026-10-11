# CastBrowser: Android Video Cast App, Tech Spec

Oct 10, 2026 · @Annas

## Overview

CastBrowser is an Android app that works as a web browser first and a TV remote second: you open a video page, the app detects the stream, and one tap plays it on a TV on the same Wi-Fi. No server of ours is involved, and the app remembers your tabs and what you watched.

**The problem.** Apps like Web Video Cast already do the detect-and-cast part. What they lack is memory. Close the app and your tabs are gone, and a week later you cannot tell which movie you were halfway through or which episode was last. This app treats "what was I watching?" as a core feature, not an afterthought.

**How it works, end to end.**

1. You browse in an in-app WebView with multiple tabs.
2. A detection engine watches network requests and page scripts and collects real media URLs (MP4, HLS, DASH).
3. A floating button shows how many videos were found. You pick one (and a quality, for HLS).
4. The app finds TVs on the LAN (DLNA first, then Chromecast) and sends the URL.
5. If the TV cannot fetch the video itself (cookies, Referer, CORS), a tiny HTTP proxy inside the app fetches it with the right headers and re-serves it.
6. While it plays, the app polls position and saves it, so history always knows where you stopped.

**What makes it different.**

- Tabs and their back/forward history survive app close, process death and reboot.
- A "Continue watching" screen lists movies and series with the last episode and resume position.
- Small footprint: system WebView (no bundled browser engine), no server, no accounts, and a release APK targeted at roughly 6 to 12 MB.

**Audience.** Version 1 is a personal tool for one primary user. The design keeps the option open to publish later, which affects the Play Store and legal notes at the end.

## Goals and non-goals

Version 1 succeeds if you can browse, cast to a TV, close the app, and next week see exactly where you left off.

**Goals**

- Multi-tab in-app browser with tabs fully restored after app close or process death.
- Reliable detection of MP4, HLS (m3u8) and DASH (mpd) streams on common video sites.
- Cast over the LAN with no backend: DLNA/UPnP first, Chromecast second.
- Persistent history with a watch log: title, site, season/episode, last position, last watched date.
- A "Continue watching" home screen.
- Release APK as small as practical (R8 full mode, resource shrinking, lean dependencies).
- Works offline-first: all data stays on the device.

**Non-goals for v1**

- DRM content (Netflix, Disney+, etc.). It cannot be extracted, and the app will say so clearly.
- iOS, desktop, or a web version.
- Screen mirroring. We cast URLs, not pixels.
- AirPlay, Miracast and Roku support (Roku is an optional later add-on).
- Downloading videos, accounts, cloud sync, or any server we run.
- A bundled browser engine such as GeckoView (adds tens of MB).

## Core features (MVP)

The MVP is six features. Each one has a short description of what the user sees and the rule behind it.

**1. Multi-tab browser**

- Address bar that accepts a URL or a search query (default search engine configurable).
- Tab switcher grid with thumbnail, title and favicon, plus new tab, close tab and close all.
- Back, forward, reload, and share. Optional desktop-site toggle per tab.
- Only a few WebViews stay alive at once; the rest are saved and rebuilt when opened (see Data model).

**2. Persistent tabs**

- Tabs are saved continuously, not only on exit, so a crash or a killed process loses nothing.
- On launch the app restores the tab list, their order, the active tab, and each tab's back/forward stack and scroll position.
- Optional "Incognito" tab that is never saved.

**3. Video detection**

- A badge on the toolbar shows how many playable streams were found on the current tab.
- Tapping it opens a sheet listing each stream with type (MP4 / HLS / DASH), resolution if known, and size or duration if known.
- Streams that are protected (DRM) or `blob:` only are shown as "cannot cast" with the reason.

**4. Cast to TV**

- Device picker listing TVs found on the network, with the last used device pinned to the top.
- Remote controller sheet: play/pause, seek bar, +/- 10 s, volume, stop, and subtitles.
- A notification with the same controls, so the phone can be locked while casting.

**5. History and watch log**

- Every visited page is stored with title, URL and time, searchable and grouped by day.
- Every cast (and every detected in-page playback) creates or updates a **watch entry**: title, site, season and episode if parsed, position, duration, last watched.

**6. Continue watching**

- The home screen shows the most recently watched items with a progress bar.
- Tapping one reopens the exact page in a new tab and offers to resume from the saved position.
- Series are grouped, showing the last watched episode (for example "Show X, S02 E05, 34 min left").

## Additional features worth adding

These are ranked by value for how you will actually use the app. P1 should land right after the MVP, P2 when time allows, P3 is optional.

| Priority | Feature | Why it helps | Size cost |
| --- | --- | --- | --- |
| P1 | Ad and popup blocker | Streaming sites are ad-heavy. Blocking known ad hosts and `window.open` popups also removes fake "videos" from detection. | Small (bundled host list, about 100 KB) |
| P1 | Next-episode button | Detects the next episode link on the page, or increments the episode in the URL, and opens it in one tap. | None |
| P1 | Manual edit of watch entries | Title and episode parsing is heuristic, so you can fix a name, mark as finished, or remove an entry. | None |
| P1 | Lock-screen and notification controls | Uses a MediaSession so volume keys and the notification control the TV. | None |
| P2 | Cast queue and auto-play next | Queue several items and let the TV continue. | None |
| P2 | External subtitles | Detect `.vtt` and `.srt` tracks on the page, or pick a file. SRT is converted to WebVTT by the proxy. Subtitle delay adjustment. | None |
| P2 | Quality picker for HLS | Parses the master playlist so you choose 480p, 720p or 1080p. | None |
| P2 | Bookmarks and Watch later | Save a page for later without starting playback. | None |
| P2 | Sleep timer | Stops casting after N minutes. Useful for movies at night. | None |
| P2 | Share intent | Share a link from any app into CastBrowser, or cast it directly. | None |
| P2 | Add device by IP | Fallback when multicast is blocked by the router. | None |
| P3 | Cast local files | Pick a video from storage through the system file picker and serve it with the proxy. | None |
| P3 | Export and import history | JSON backup so a new phone keeps your watch log. | None |
| P3 | Local preview player (Media3) | Test a stream on the phone before casting. | Adds roughly 1 to 2 MB |
| P3 | Roku support | Roku's HTTP control API on port 8060. | None |

Privacy options to include from the start: clear history on exit, per-site "do not record", and an incognito tab.

## Architecture

A web page becomes a TV stream through a few parts that all run on the phone, and the TV only needs a URL it can fetch.

&#91;embedded content: architecture · 6 parts, 1 proxy\]

The UI talks to three engines: the browser holds the tabs, detection finds streams, and the cast layer controls the TV. The browser, detection and cast status all write to the data store, which is how tabs, history and resume positions survive. When the TV can fetch a stream itself, the proxy is skipped and the TV gets the original URL.

**Code layout.** One Gradle module with packages `browser`, `detect`, `cast`, `proxy`, `data` and `ui`. Each engine exposes a small interface and a `Flow` of state, and the UI only collects those flows. A simple app container creates the engines and the foreground service owns the proxy and the status polling during a cast.

## Tech stack

The stack is chosen for two things: full control of the WebView and a small APK. Where a choice trades size for convenience, the table says so.

| Area | Choice | Reason |
| --- | --- | --- |
| Language | Kotlin (2.x) | Native Android, coroutines and Flow for discovery and status streams. |
| UI | Jetpack Compose + Material 3 | Requested. Use only the core icon set and copy extra icons as vector XML, not `material-icons-extended`. |
| Browser engine | System `android.webview.WebView` inside `AndroidView` | Zero bytes added, and it exposes `shouldInterceptRequest`. GeckoView would add tens of MB. |
| WebView helpers | `androidx.webkit` | `addDocumentStartJavaScript` and `addWebMessageListener` for safe script injection and messaging. |
| Storage | Room (with KSP) + Preferences DataStore | Tabs, history and watch log in Room. Settings in DataStore. |
| WebView state files | Plain files in `filesDir` | Each tab's saved WebView state is a small binary file, not a database blob. |
| Networking | OkHttp | Probing stream URLs, proxy upstream fetches, SOAP calls. Handles cookies and headers explicitly. |
| Local proxy server | NanoHTTPD (or a minimal `ServerSocket` server) | Tiny. Ktor adds more weight than needed. |
| DLNA/UPnP | Own small implementation | SSDP over `DatagramSocket`, SOAP over OkHttp, XML via `XmlPullParser`. No library, no extra size. |
| Chromecast | Google Cast SDK (`play-services-cast-framework`) | Most reliable path. Needs Google Play Services. An own CASTV2 client is a possible later replacement. |
| Images | Coil (or none) | Only for favicons and thumbnails. Thumbnails can also be plain `Bitmap` files. |
| Dependency injection | Manual (a simple app container) | Avoids Hilt/Dagger size and build time for a project this size. |
| Navigation | Navigation Compose | Home, Tabs, History, Settings. |
| Background | Foreground service (type `mediaPlayback`) + `MediaSession` | Keeps the proxy and position polling alive while casting. |
| Build | Gradle Kotlin DSL, version catalog, AGP 8.x | Standard. |
| Min / target SDK | minSdk 26, targetSdk latest stable | Android 8.0+ covers nearly all devices, and simplifies APIs. |

**Module layout.** Start as one Gradle module with clear packages (`browser`, `detect`, `cast`, `proxy`, `data`, `ui`). Split into modules only if build time becomes a problem.

## Video detection engine

Detection combines three signals, because no single one catches every site: network requests, injected page scripts, and the DOM. Candidates then go through filtering, probing and scoring before the user sees them.

**Signal 1: network interception.** `WebViewClient.shouldInterceptRequest` sees every request the page makes, including iframes. Each request URL is classified by extension and pattern (`.mp4`, `.m3u8`, `.mpd`, `.webm`, `/manifest`, `videoplayback`). The request headers (`Referer`, `User-Agent`, `Origin`) are stored with the candidate, and cookies are read from `CookieManager` when needed. The callback runs on a background thread, so it only classifies and posts to a channel. It never blocks.

**Signal 2: injected scripts.** A script added with `addDocumentStartJavaScript` runs before the page's own code, in all frames allowed by the origin rules. It hooks `fetch`, `XMLHttpRequest` and the `src` setter of `HTMLMediaElement`, and watches new `<video>` and `<source>` nodes with a `MutationObserver`. Findings go back to Kotlin through `addWebMessageListener`, which is safer than `addJavascriptInterface`. The same script reports `currentTime`, `duration`, and play/pause every few seconds for watch progress. If the WebView does not support document-start scripts, the fallback is injecting in `onPageStarted`.

**Signal 3: page metadata.** `og:video`, `<video>` tags, `<track>` subtitle elements, and the page title are read for naming and subtitles.

**From candidate to castable stream**

1. **Filter.** Drop HLS/DASH media segments (`.ts`, `.m4s`, `.aac`). Keep playlists and manifests only. Drop tracking pixels and very short clips (likely ads).
2. **Probe.** A `HEAD` or `Range: bytes=0-1` request through OkHttp, with the captured headers and cookies, checks that the URL works and returns the real `Content-Type` and `Content-Length`.
3. **Expand.** For an HLS master playlist, parse the variants (bandwidth, resolution) so the user can pick quality. For DASH, list representations.
4. **Classify delivery.** Re-probe without cookies, `Referer` and custom `User-Agent`. If it still works, the TV can fetch it directly. If not, mark it as **needs proxy**.
5. **Check protection.** `#EXT-X-KEY` with a method other than `AES-128`, `encrypted` events on the media element, or Widevine signs mean DRM. Mark as not castable.
6. **Dedupe and rank.** Same URL or same playlist family counts once. Rank by type, resolution and whether it was actually played in the page.

**Known hard cases**

| Case | What happens | Handling |
| --- | --- | --- |
| `blob:` URLs | The real source is a MediaSource fed by segments | Capture the manifest or segment URLs via fetch/XHR hooks. Otherwise show "cannot cast". |
| Signed URLs that expire | The link stops working within minutes | Cast soon after detection, re-detect on failure. |
| IP-locked URLs | The TV has a different IP than the phone | Use the proxy: the phone fetches the video, so the CDN sees the phone's IP. |
| Player inside a cross-origin iframe | Scripts do not reach it by default | Use origin rules `*` on the message listener and the document-start script. |
| DRM | Encrypted content | Detect and refuse with a clear message. |

## Casting layer and local proxy

Casting sits behind one interface, so the UI never knows whether a TV speaks DLNA or Cast. Each protocol is an adapter that does discovery and control.

```kotlin
interface CastDevice { val id: String; val name: String; val protocol: Protocol }

interface CastController {
    suspend fun load(media: CastMedia, startMs: Long)
    suspend fun play(); suspend fun pause(); suspend fun stop()
    suspend fun seekTo(ms: Long)
    suspend fun setVolume(level: Float)
    val status: Flow<PlaybackStatus>   // position, duration, state
}
```

| Protocol | Discovery | Control | Notes |
| --- | --- | --- | --- |
| DLNA/UPnP (phase 1) | SSDP `M-SEARCH` to `239.255.255.250:1900`, search target `MediaRenderer:1`. Fetch the device XML from `LOCATION` and read the `AVTransport` control URL. | SOAP over HTTP: `SetAVTransportURI` (with DIDL-Lite metadata), `Play`, `Pause`, `Seek` (`REL_TIME`), `Stop`, `GetPositionInfo`, `GetTransportInfo`. Volume via `RenderingControl`. | No dependencies. Poll position every 1 to 2 s. Many TVs need correct `protocolInfo` such as `http-get:*:video/mp4:*`. |
| Chromecast (phase 2) | Cast SDK `MediaRouter` (mDNS `_googlecast._tcp` under the hood). | `RemoteMediaClient.load` with `MediaInfo` (content type, stream type, subtitle tracks). Default Media Receiver. | Needs Play Services. Media must be CORS-enabled, which the proxy provides. |
| Roku (optional) | SSDP `roku:ecp` | HTTP to port 8060 | Experimental, after v1. |

**Choosing direct or proxied delivery.** The detection step marks each stream "direct" or "needs proxy". Direct streams send the original URL to the TV. Proxied streams send `http://<phone-ip>:<port>/s/<token>`.

**What the proxy does**

- Fetches upstream with the captured `Referer`, `User-Agent` and cookies through OkHttp.
- Passes `Range` requests through and returns correct `206 Partial Content`, so seeking works.
- Rewrites HLS playlists so every segment and sub-playlist URL also goes through the proxy (relative and absolute URLs).
- Adds CORS headers and fixes content types (for example `application/vnd.apple.mpegurl`).
- Converts SRT subtitles to WebVTT and serves them next to the video.
- Serves local files with Range support.

**Safety rules for the proxy**

- Bind to the Wi-Fi interface only. Each stream gets a random token, valid only for that cast session.
- Start the server when casting starts and stop it when the session ends. It runs inside the foreground service.
- Proxied video is downloaded and uploaded by the phone, so it uses about twice the Wi-Fi bandwidth and some battery. This is why direct delivery is preferred.

**Discovery requirements.** Hold a `WifiManager.MulticastLock` while scanning, or Android drops multicast replies. Cache the last used device and try it first. Offer "Add device by IP" for routers that block multicast or isolate clients.

## Data model and persistence

Everything is stored locally in a Room database plus a few files. Three tables carry the feature you asked for: tabs, history, and the watch log.

| Table | Key columns | Purpose |
| --- | --- | --- |
| `tab` | `id`, `url`, `title`, `position`, `isActive`, `isIncognito`, `lastAccessedAt`, `stateFile`, `thumbFile`, `faviconFile` | One row per open tab, in order. |
| `history_visit` | `id`, `url`, `title`, `host`, `visitedAt` | Every page visited (not incognito). Indexed by `visitedAt` and `host`. |
| `watch_entry` | `id`, `pageUrl`, `title`, `host`, `seriesKey`, `season`, `episode`, `posterUrl`, `positionMs`, `durationMs`, `finished`, `lastWatchedAt` | One row per movie or episode. Drives Continue watching. |
| `series` | `seriesKey`, `displayName`, `lastEntryId`, `updatedAt` | Groups episodes of one show and points to the last one watched. |
| `bookmark` | `id`, `url`, `title`, `createdAt`, `watchLater` | Saved pages. |
| `cast_device` | `id`, `name`, `protocol`, `address`, `lastUsedAt` | Last used TVs, for quick reconnect. |

Settings (search engine, clear-on-exit, ad block, default quality) live in DataStore.

**Saving and restoring tabs**

1. **What is saved.** For each tab, `WebView.saveState(Bundle)` captures the back/forward list and scroll position. The Bundle is written to a file `filesDir/tabs/<id>.bin`, and the tab row stores the path. A small thumbnail is saved as a WebP file.
2. **When it is saved.** On tab switch, `onPageFinished`, `onStop`, and a debounced timer (for example 5 s after the last change). Writes happen on a background dispatcher, and the tab list is written in one Room transaction. This covers process death, where `onDestroy` may never run.
3. **Restore on launch.** Read the `tab` table, recreate only the active tab's WebView, and show the others as thumbnail cards. A tab's WebView is created the first time you open it, with `restoreState`.
4. **Memory control.** Keep at most 3 live WebViews (LRU). When one is evicted, save its state first and drop the view.
5. **Fallback.** If `restoreState` fails (for example after a WebView update), load the stored `url` instead. The tab still opens at the right page, only the back stack is lost.

**Watch log: how an entry is created and updated**

- **Created** when a stream is cast, or when the injected script reports a video playing in-page for more than about 30 s.
- **Updated** every 5 to 10 s while casting (from `GetPositionInfo` or Cast media status) or while playing in-page (from the script's `currentTime`), and once more when playback stops.
- **Finished** when position passes about 95 percent of duration.
- **Resume** uses `positionMs`. DLNA seeks after `Play` once the TV reports `PLAYING`, and Cast passes `currentTime` in the load request.

**Title and episode parsing.** A small rule-based parser reads the page title, URL and headings. It recognizes `S02E05`, `2x05`, `Episode 5`, `Ep. 5`, `/episode-5/`, and strips site names like "Watch ... online". The result sets `seriesKey`, `season`, `episode`. If nothing matches, the entry is treated as a movie. You can always edit it manually.

**Privacy.** Incognito tabs write no history, no watch entries and no state files. "Clear on exit" removes history and cookies. Android Auto Backup should exclude state files and cookies, and may include the database if you want restore on a new phone.

## Keeping the APK small

The target is a universal release APK of roughly 6 to 8 MB without the Cast SDK and 9 to 12 MB with it. These are planning estimates, not measurements, and the first release build will tell us the real number.

Most of the saving comes from what we choose not to ship: no browser engine, no Hilt, no icon pack, no JSON library, no native code.

**Build settings**

```kotlin
android {
    defaultConfig {
        minSdk = 26
    }
    androidResources {
        localeFilters += listOf("en", "id")   // drop unused library translations
    }
    buildTypes {
        release {
            isMinifyEnabled = true            // R8 shrink, optimize, obfuscate
            isShrinkResources = true          // remove unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
}
```

**Checklist**

- Use R8 in full mode (default in recent AGP) and keep rules minimal. Add keep rules only for the Cast SDK and anything used through reflection.
- Publish an **AAB** to Google Play so devices get split APKs by ABI and density. For sideloading, ship one universal APK.
- No native libraries means no ABI splits are needed. Avoid dependencies that bundle `.so` files.
- Do not use `material-icons-extended`. Copy the 15 to 20 icons you need as vector drawables.
- Prefer vector drawables and WebP over PNG. Avoid bundled fonts; use the system font.
- Skip Hilt, Gson, Retrofit and Glide. Room handles storage, `XmlPullParser` and `org.json` handle the small parsing needs, and OkHttp covers HTTP.
- Keep the ad-block host list compact (plain text, one host per line).
- Add a Baseline Profile for faster startup. It costs little size and helps Compose cold start.
- Track size in CI with a budget (for example fail the build if the release APK grows by more than 500 KB), and inspect it with Android Studio's APK Analyzer.

**Dependency size watch list**

| Dependency | Size impact | Decision |
| --- | --- | --- |
| System WebView | 0 (already on device) | Use |
| Compose + Material 3 | Largest single piece, trimmed well by R8 | Use |
| OkHttp | About 1 MB before shrinking | Use |
| Room | Small | Use |
| Google Cast SDK | A few MB with its Play Services dependencies | Use in phase 2, measure |
| Media3 / ExoPlayer | About 1 to 2 MB | Optional, only for local preview |
| GeckoView | Tens of MB | Do not use |

## Permissions and platform gotchas

Most problems in this kind of app come from networking and background limits, not from the UI. These are the ones to plan for.

| Topic | Requirement or risk | Handling |
| --- | --- | --- |
| Network permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Declare in the manifest. |
| Multicast | SSDP replies are dropped without a `MulticastLock` | Acquire during scans, release right after. |
| Nearby devices | Newer Android versions add a runtime permission for local network or nearby Wi-Fi devices | Check the target SDK rules at build time, and request only when scanning starts. |
| Foreground service | Android 14 and later needs a declared service type, and Android 15 limits some start paths | Use `mediaPlayback`, declare `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and start it from a user action. |
| Notifications | `POST_NOTIFICATIONS` runtime permission on Android 13+ | Ask when the first cast starts, with a short reason. |
| Cleartext HTTP | The proxy and many TVs use plain `http://` on the LAN | Allow cleartext only for private LAN ranges through a network security config. |
| Router client isolation | Phone and TV cannot see each other | Detect "no devices found" and show a hint, plus Add device by IP. |
| VPN or mobile data | Casting needs both devices on the same subnet | Show a warning when the phone is not on Wi-Fi. |
| Mixed content | HTTPS pages loading HTTP media | Enable mixed content only if needed, and prefer the proxy. |
| Popups and redirects | Streaming sites open ad tabs | Block `window.open` without a user gesture, and offer "open in new tab" only on real taps. |
| Safe browsing | Risky sites | Keep WebView's Safe Browsing enabled. |
| Battery | Proxy plus polling keep the radio active | Stop everything when the session ends, and poll no faster than 1 to 2 s. |
| WebView updates | Behavior differs across WebView versions | Feature-check with `WebViewFeature` before using each `androidx.webkit` API. |

## Milestones and roadmap

The build order follows risk: the unknown parts (detection and TV compatibility) come before the polish.

&#91;embedded content: roadmap · 5 phases, 3 gates\]

Each phase ends with something you can run on a real phone. Phase 3 is the first time a video plays on the TV, and phase 4 delivers the persistent history and Continue watching. Chromecast and the size pass come last because they build on everything before them. No dates are set; give me the hours you can spend per week and I can turn this into dated milestones.

## Risks, legal notes and open questions

The biggest technical risk is that some sites will always hide their streams, and the biggest product risk is distribution rules if the app is ever published.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Sites obfuscate or rotate stream URLs | Detection misses videos | Combine all three signals, keep the detector rules updatable, and add a manual "paste stream URL" option. |
| TV firmware quirks in DLNA | Playback fails or seek breaks | Test on at least 2 to 3 real TVs, keep per-brand metadata tweaks, and log the SOAP errors. |
| Cast SDK needs Play Services | No Chromecast on devices without it | Keep DLNA as the base path. |
| Heuristic episode parsing is wrong | Wrong series grouping | Manual edit, and store the raw page title too. |
| WebView state restore breaks after updates | Lost back stack | Fall back to the stored URL. |
| Memory pressure with many tabs | Crashes | Keep 3 live WebViews at most, and evict with state saved. |

**Legal and store notes**

- The app is a browser and a player controller. It hosts no content and does not bypass DRM, and it should refuse protected streams.
- If published on Google Play, review the policies on deceptive behavior, intellectual property and malware. Apps that mainly help pirate content get removed. A personal sideloaded build avoids this, but the responsibility for what you open stays with the user.
- Do not log or upload browsing data. The app has no analytics by default.

**Open questions**

- [ ] Personal tool only, or do you plan to publish it (Play Store or sell it)? This changes how strict the policy and legal work is.
- [ ] Which TVs do you have for testing (brand and model)? DLNA behavior differs a lot between brands.
- [ ] Is Chromecast needed for v1, or is DLNA enough to start?
- [ ] Do your target devices all have Google Play Services?
- [ ] Preferred app name and package ID (the working title is CastBrowser).
- [ ] Default search engine, and whether to ship the ad blocker in v1 or the release after.
