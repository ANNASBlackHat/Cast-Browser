# CastBrowser Implementation Plan (Phases 1 to 5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete native Android video cast browser with persistent multi-tab management, multi-signal stream detection (MP4/HLS/DASH), DLNA/UPnP and Chromecast casting with local streaming proxy, and local watch history / Continue Watching UI, culminating in a signed/tested release APK uploaded to Telegram.

**Architecture:** Single Android module with clean layered packages (`browser`, `detect`, `cast`, `proxy`, `data`, `ui`). Manual dependency injection with `AppContainer`, SQLite Room with Coroutines/Flow, OkHttp with embedded NanoHTTPD local proxy, and Jetpack Compose Material 3 UI.

**Tech Stack:** Kotlin 2.1+, Jetpack Compose + Material 3, AndroidX WebKit, Room + DataStore, OkHttp 4.x, NanoHTTPD 2.3+, DLNA (SSDP + SOAP), Android Cast SDK, Coroutines/Flow.

**Spec:** `.specs/CastBrowser Android Video Cast App, Tech Spec.md`

## Global Constraints
- Target SDK: 35, Min SDK: 26.
- Architecture: Kotlin native, Jetpack Compose Material 3, System WebView (no GeckoView).
- Manual AppContainer (no Dagger/Hilt to keep APK footprint minimal).
- Room DB with KSP + DataStore Preferences.
- Offline-first: all tabs, history, and watch states stored in device database and `filesDir`.
- Proxy runs only during active cast via Foreground Service (`mediaPlayback`).
- Release APK must be minified, shrinkResources enabled, and under 15 MB.

---

### Task 1: Project Scaffolding & Build Configuration (Phase 1 Foundation)
- [ ] Initialize gradle wrapper (Gradle 8.12) and root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `local.properties`.
- [ ] Configure `app/build.gradle.kts` with plugins (Android 8.8.2, Kotlin 2.1.10, Compose, KSP, Serialization).
- [ ] Configure dependencies: Compose BOM, Room 2.6.1, WebKit 1.12.1, OkHttp 4.12.0, NanoHTTPD 2.3.1, Cast Framework (or mock/DLNA foundation), JUnit4, Truth.
- [ ] Create `app/src/main/AndroidManifest.xml` with permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`) and network security config for LAN cleartext HTTP.
- [ ] Verify build with `./gradlew assembleDebug`.

### Task 2: Data Layer & Persistence Models (Phase 1 & Spec Section "Data model")
- [ ] Create entities: `TabEntity`, `HistoryVisitEntity`, `WatchEntryEntity`, `SeriesEntity`, `BookmarkEntity`, `CastDeviceEntity`.
- [ ] Create Room DAOs: `TabDao`, `HistoryDao`, `WatchEntryDao`, `BookmarkDao`, `CastDeviceDao`.
- [ ] Create `AppDatabase` with Room migrations / initial schema and converter helpers.
- [ ] Write unit tests for DAOs (`AppDatabaseTest`).
- [ ] Verify with `./gradlew testDebugUnitTest`.

### Task 3: Local File Store & Tab State Manager
- [ ] Implement `TabStateManager` to save/restore WebView `Bundle` binary state to `filesDir/tabs/<id>.bin` and tab thumbnails.
- [ ] Implement episode and title parser (`EpisodeTitleParser`) according to spec (`S02E05`, `2x05`, `Episode 5`, etc.).
- [ ] Write unit tests for `EpisodeTitleParserTest` and `TabStateManagerTest`.
- [ ] Verify tests pass.

### Task 4: Video Detection Engine (Phase 2 & Spec Section "Video detection engine")
- [ ] Implement `DetectedStream` model (URL, headers, stream type: MP4/HLS/DASH, resolution, size, direct vs proxied, isDrm).
- [ ] Implement `StreamClassifier` to inspect URLs, extensions, and content headers.
- [ ] Implement `StreamProbeService` with OkHttp (HEAD and Range probing to classify direct vs proxied, DRM checks).
- [ ] Implement JavaScript injection scripts (`video_detector.js`) for `addDocumentStartJavaScript` and `MutationObserver` / media element hooks.
- [ ] Write unit tests for `StreamClassifierTest` and `StreamProbeServiceTest`.

### Task 5: Local Streaming Proxy Server (Spec Section "Local proxy")
- [ ] Implement `LocalStreamProxy` using NanoHTTPD running on LAN IP.
- [ ] Implement upstream header/cookie forwarding with OkHttp.
- [ ] Implement `206 Partial Content` Range request support for seeking.
- [ ] Implement HLS master/variant and segment URL rewriter.
- [ ] Implement WebVTT converter for external SRT subtitles.
- [ ] Write unit tests for `LocalStreamProxyTest`.

### Task 6: Casting Layer (DLNA & Chromecast) (Phase 3 & Spec Section "Casting layer")
- [ ] Implement unified casting interfaces: `CastDevice`, `CastController`, `PlaybackStatus`.
- [ ] Implement DLNA SSDP discovery engine with `MulticastLock`.
- [ ] Implement DLNA UPnP SOAP controller (`SetAVTransportURI`, `Play`, `Pause`, `Seek`, `Stop`, `GetPositionInfo`).
- [ ] Implement Chromecast adapter or stub/clean interface.
- [ ] Write unit tests for DLNA SOAP generator and XML response parser.

### Task 7: Background Cast Service & MediaSession (Phase 3)
- [ ] Implement `CastForegroundService` (type `mediaPlayback`) managing active proxy lifecycle and playback status polling.
- [ ] Integrate Android `MediaSessionCompat` for notification controls and lock-screen playback.
- [ ] Hook playback progress polling (every 5-10s) back into `WatchEntryDao` for auto-resume.

### Task 8: In-App Browser & WebView Integration (Phase 1 & Phase 2 UI)
- [ ] Implement `BrowserEngine` / `CustomWebViewClient` hooking `shouldInterceptRequest`, Safe Browsing, and popup controls.
- [ ] Implement LRU WebView pool (max 3 active WebViews).
- [ ] Implement Ad/Popup blocker engine with domain blocklist.
- [ ] Wire detection events from WebView to app state.

### Task 9: Jetpack Compose UI (Phase 1 to Phase 4 UI)
- [ ] Implement Theme & Navigation (`NavHost`): Home/Continue Watching, Browser/Tabs, History, Settings.
- [ ] Implement `BrowserScreen`: URL bar, WebView container, tab badge, floating video detection sheet.
- [ ] Implement `TabSwitcherScreen`: tab cards with thumbnails, close, new tab.
- [ ] Implement `CastSheet` & Remote Control dialog: device discovery, quality picker, playback controls (seek, volume, pause).
- [ ] Implement `HomeScreen` (Continue Watching list, quick resume, series grouping).
- [ ] Implement `HistoryScreen` with search and delete.

### Task 10: Full Integration & Testing Pass
- [ ] Run complete test suite `./gradlew testDebugUnitTest`.
- [ ] Build release APK `./gradlew assembleRelease`.
- [ ] Verify release APK size (target < 15MB) and R8 shrink configuration.

### Task 11: Telegram Upload & Final Report
- [ ] Write upload script using `TELEGRAM_TOKEN` and `TELEGRAM_CHAT_ID`.
- [ ] Upload the built release APK with build summary and features checklist.
