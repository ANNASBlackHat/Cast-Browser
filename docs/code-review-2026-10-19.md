# CastBrowser — Code Logic Analysis, Flaws & Potential Bugs

**Date:** 2026-10-19
**Scope:** All of `app/src/main` (44 Kotlin files + `app/src/main/assets/video_detector.js`, ~4,000 LOC) and the 6 unit test classes under `app/src/test`.
**Commit:** `ae59ff9` (main)

## Method & verification

- The codebase-memory knowledge-graph index for this repo contains **no code nodes** (only folder/file structure), so this analysis is based on a **complete manual read** of the source, cross-checked with targeted greps.
- **Build/tests run:** `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, all 6 test classes pass (so the code compiles, including the unusual `activeController?.status?.collectAsState()` pattern).
- **Bug C4 was proven empirically** with a throwaway harness compiled against the exact `nanohttpd-2.3.1.jar` from the Gradle cache (see C4 output; harness deleted afterwards).
- **Limitation:** runtime behaviors on a real TV/LAN (DLNA handshake, SSDP strictness) could not be exercised in this environment.

## 1. Code logic overview

- **`CastApplication` → `AppContainer`** — manual DI: Room DB → `AppRepository`, DataStore `UserSettings`, `TabStateManager`, `StreamProbeService`, `WebViewPool`, `DlnaDiscoveryService`.
- **`MainActivity`** — single Compose `NavHost` (`browser/tabs/history/home`), holds `activeTabId`, device-scan state, and `activeController` (DLNA cast controller).
- **Browse path:** `BrowserScreen` hosts a pooled `WebView` → `CastWebViewClient.shouldInterceptRequest` runs AdBlock + stream-URL classification → async `StreamProbeService.probeStream` (Range probe + direct-access test → `DIRECT`/`NEEDS_PROXY`) → results into `WebViewPool.tabStreams` StateFlow → `VideoDetectionSheet` → `DevicePickerSheet` (SSDP scan) → `onStartCast`.
- **Cast path:** `DlnaCastController` sends SOAP (`SetAVTransportURI`/`Play`/`Seek`…), polls `GetPositionInfo` every 1.5 s; `NEEDS_PROXY` streams get a local NanoHTTPD proxy at `:8888` with HLS playlist rewriting.
- **Persistence:** Room tabs/history/watch-entries; WebView back/forward state via `Parcel` files; watch progress is *meant* to be written by `CastForegroundService` (it never is — see C1).

## 2. Critical bugs (features broken)

### C1. The foreground cast service is never wired up — watch progress & notifications are dead code

`CastForegroundService.startCasting(...)` (`CastForegroundService.kt:81`) is **never called anywhere** (grep-verified). In `MainActivity.kt:127-134` instead:

```kotlin
startService(serviceIntent)                                    // no action → onStartCommand does nothing
(CastForegroundService.activeControllerInstance as? DlnaCastController)  // discarded expression
CastForegroundService().apply { }                              // manually new-ing a Service — never attached, does nothing
controller.load(castMedia, 0L)
```

Consequences: no notification/media session, and `recordOrUpdateWatch(...)` (`CastForegroundService.kt:112`) **never runs → "Continue Watching" on HomeScreen is always empty**. The JS `PLAYBACK_PROGRESS` messages (`video_detector.js:82`) are also ignored — `handleJsMessage` (`CastWebViewClient.kt:103`) only handles `STREAM_CANDIDATE`. The entire watch-progress feature has **no live writer**.

### C2. Cold start shows a phantom `"default_tab"` WebView

`MainActivity.kt:58-77` restores the real active tab in a `LaunchedEffect` (runs *after* first composition), but `BrowserScreen.kt:64` computes `currentTabId = activeTab?.id ?: "default_tab"` and passes it to `AndroidView(factory = ...)` (line 220), which **runs once and never re-runs** (no `update {}` block).

On every cold start the user gets a WebView bound to a non-existent `"default_tab"` loading google.com, while the address bar shows the *restored* tab's URL (keyed on `activeTab?.url`, line 54) — **URL bar and page content disagree**; streams are recorded under `"default_tab"`; the restored tab's URL in the DB gets overwritten by the phantom WebView's navigations via `onUrlNavigated`.

### C3. Opening a URL from Home/History never navigates the page

`HomeScreen`/`HistoryScreen` callbacks only do `repository.saveTab(current.copy(url = url))` (`MainActivity.kt:211, 229`), then re-enter `BrowserScreen`. The pool returns the **cached** WebView and ignores the requested URL:

```kotlin
// WebViewPool.kt:44
activeViews[tabId]?.let { return it }   // initialUrl is discarded
```

Result: the address bar shows the new URL while the WebView still shows the old page. (Only the in-page URL-bar overlay, which calls `webViewInstance.loadUrl` directly, works.)

### C4. Proxy double-decodes the target URL — proven to corrupt any URL containing `+` or `%`

`LocalStreamProxy.kt:58-62` runs `URLDecoder.decode(rawTargetUrl, "UTF-8")` on a value **NanoHTTPD has already percent-decoded**. Verified empirically with the exact `nanohttpd-2.3.1.jar`:

```
TARGET =[https://cdn.example.com/v?sig=a+b&next=%2Fhome]
NANO_PARMS_URL=[https://cdn.example.com/v?sig=a+b&next=%2Fhome]   ← NanoHTTPD already decoded
AFTER_SECOND_DECODE=[https://cdn.example.com/v?sig=a b&next=/home] ← CORRUPTED=true
```

`+` → space, `%2F` → `/` → **signed CDN URLs (base64 sigs, next= params) break with 403** exactly for the streams that need the proxy. The same `URLEncoder.encode` is used in `MainActivity.kt:115-117` and `HlsPlaylistRewriter.kt:65`.

### C5. Proxy is never stopped → second cast crashes the app

`proxy` in `onStartCast` (`MainActivity.kt:109-118`) is a local variable; the only `proxyServer?.stop()` is in `CastForegroundService.onDestroy` (`:274-276`) — but the service never receives it (C1). So port **8888 stays bound forever**; the next cast calls `LocalStreamProxy(8888).start()` → bind failure → `IOException` thrown inside `scope.launch` on Main → **uncaught → app crash**. Also a port/thread leak per session.

### C6. Tab titles and history titles are never updated

Nothing ever writes a new title back to Room: `saveTab(current.copy(url = updatedUrl))` (`MainActivity.kt:153, 211, 229`) never touches `title`, and `onTitleUpdate` only sets local Compose state (`BrowserScreen.kt:226`). So:

- `recordVisit(updatedUrl, current.title)` (`MainActivity.kt:154`) logs **every history entry as "Google" / "New Tab"**;
- the tab-switcher grid shows the creation-time title forever.

## 3. High-severity logic flaws

| # | Flaw | Where |
|---|---|---|
| H1 | **"Hide" on the remote sheet is impossible**: `LaunchedEffect(castingPlaybackStatus)` re-runs on *every* 1.5 s poll emission and reopens the sheet (`if (status != null && !showRemoteSheet) showRemoteSheet = true`) | `BrowserScreen.kt:78-82` |
| H2 | **Open proxy / LAN SSRF**: token is optional and unenforced — `activeStreams[token] ?: emptyMap()` still proxies any URL; NanoHTTPD binds all interfaces. Any LAN device can use the phone to fetch `http://router/...` etc. | `LocalStreamProxy.kt:64`, `:14` |
| H3 | **Cleartext allowed for ALL domains**: `network_security_config.xml` uses `<base-config cleartextTrafficPermitted="true">` though the comment claims "local private networks" — should be a `<domain-config>` for the proxy only | `res/xml/network_security_config.xml` |
| H4 | **`getLocalIpAddress()` returns the first non-loopback IPv4 of *any* interface** — on a phone with cellular up, that's an `rmnet` IP the TV can't reach → proxied cast silently fails. Must prefer `wlan0`/interface with a route to the TV | `MainActivity.kt:240-255` |
| H5 | **Back press is always swallowed**: `BackHandler(enabled = true)` no-ops when the WebView can't go back → at root the user cannot exit with back | `BrowserScreen.kt:70-76` |
| H6 | **Closing the last tab** leaves `activeTabId = null` → phantom `"default_tab"` again; DB `isActive` flag never repaired (no `setActiveTab` after close) → two/zero active tabs possible | `MainActivity.kt:176-179` |
| H7 | **Re-casting never releases the previous controller**: `onStartCast` overwrites `activeController` without `stop()/release()` → the old controller's `while(isActive)` poll loop keeps sending GetPositionInfo SOAP to the *old TV* forever. No `release()` call exists anywhere in UI | `MainActivity.kt:102-137`, `DlnaCastController.kt:95-103` |
| H8 | **Resume feature missing**: `controller.load(castMedia, 0L)` — watch position is never passed as `startMs` despite `WatchEntryEntity.positionMs` existing | `MainActivity.kt:135` |
| H9 | **Back/forward history lost on process death**: `WebViewPool.saveTabState()` has **zero callers** — state is only saved on LRU eviction. Spec claims "tabs survive app close and reboot"; only URL/title do | `WebViewPool.kt:106` |

## 4. Medium / correctness issues

- **M1 — Lost stream discoveries (race):** `_tabStreams` read-modify-write from concurrent probe coroutines on `Dispatchers.IO` (`toMutableMap()` … assign) is not atomic → concurrent discoveries drop each other; late probes also **re-add streams for tabs already cleared/removed** (`WebViewPool.kt:130-144`, `CastWebViewClient.kt:47-52`).
- **M2 — Probe amplification:** every resource matching `isPotentialStreamUrl` triggers up to **3 HTTP requests** (Range probe + HEAD fallback + `testDirectAccess`), with **no dedup** — same URL re-probed per request and per JS notification. Substring rules `/manifest`, `/master`, `/playlist` (`StreamClassifier.kt:63-65`) false-positive on e.g. `manifest.json`.
- **M3 — Invalid HTTP from proxy:** `newChunkedResponse(...)` **plus** a manually added `Content-Length` header (`LocalStreamProxy.kt:113-119`) — `Transfer-Encoding: chunked` + `Content-Length` together is spec-violating; strict TV players may hang/reject.
- **M4 — Case-sensitive HLS detection in proxy:** `targetUrl.substringBefore("?").endsWith(".m3u8")` — a `.M3U8` URL with a generic content type is served unrewritten → unplayable segments (`LocalStreamProxy.kt:88`).
- **M5 — `EpisodeTitleParser` range bugs:** matches are found in `rawTitle` but ranges applied to the junk-stripped `cleanTitle` (`:35, :48, :61, :74`) → misaligned/empty series keys when junk suffixes were removed; `substring(matchRange.last)` at `:104` should be `last + 1` (off-by-one includes a match char).
- **M6 — DRM handling is fake:** `contentType?.contains("drm")` (`StreamProbeService.kt:74`) is essentially never true (DRM shows up in manifests/licenses), and the UI's "Cannot cast" label doesn't disable the row — DRM streams cast anyway and just fail on the TV.
- **M7 — SSDP robustness:** the raw-string `searchMessage` **starts with a newline** (Kotlin keeps it; → leading CRLF before `M-SEARCH` — tolerated only by RFC 7230-lenient parsers) (`DlnaDiscoveryService.kt:24-31`); `socket` isn't closed if an exception escapes the receive loop (`:44-71`, only closed on success path); devices aren't deduped by UDN so the same TV can appear twice.
- **M8 — History hygiene:** every navigation inserts a **new row** (`@Insert(REPLACE)` with `id=0` autoincrement never conflicts) → duplicates + unbounded growth; visits recorded for every redirect; host falls back to full URL (`AppRepository.kt:69-83`).
- **M9 — Compose/UX:** URL-bar state keyed on `activeTab?.url/title` resets `currentDisplayTitle` to the stale DB title after each save (title flicker) (`BrowserScreen.kt:54-55`); search overlay runs a Room query **per keystroke** with no debounce and switches flows mid-composition (`HistoryScreen.kt:26-30`); the seek slider fires `onSeek` on **every drag tick** — floods the TV with SOAP `Seek`s (no `onValueChangeFinished`) (`RemoteControlSheet.kt:66-72`).
- **M10 — Coroutine hygiene:** `CastWebViewClient.clientScope` (`:28`) is never cancelled — leaks per created WebView; `DlnaCastController.pollPosition` uses blocking `execute()` that coroutine cancellation can't interrupt; `DlnaXmlHelper.formatRelTime` uses `String.format` without `Locale.US` (non-ASCII digits under e.g. `ar` locales → TVs can't parse seeks).
- **M11 — Config drift in proxy:** `activeSessionToken` and `unregisterStream` are dead (`LocalStreamProxy.kt:16, 29`); plain `HashMap activeStreams` is mutated from the UI thread and NanoHTTPD worker threads with **no synchronization** (`:17-27`).

## 5. Dead code / spec-vs-implementation gaps (grep-verified)

1. **Incognito** — hardcoded `isIncognito = false` at every call site; the field/path is never exercised.
2. **All of `UserSettings`** — `adBlockEnabled` never read (AdBlock always on), `searchEngine` never read (`BrowserScreen.kt:137` hardcodes Google), `KEY_CLEAR_ON_EXIT` never used; no setters called anywhere.
3. **Bookmarks** — `BookmarkEntity`/`BookmarkDao` registered in the DB but not exposed by `Repository` and never used.
4. **Cast-device persistence** — `saveDevice`/`getLastUsedDevice` never called from UI.
5. **Tab thumbnails / `stateFile` / `thumbFile`** — directories created, files never written.
6. **README claims "AndroidX WebKit document-start script injection"** — `WebViewCompat.addDocumentStartJavaScript` is never used; injection happens only in `onPageFinished`, so `fetch/XHR/media-src` hooks miss everything during initial page load (partially covered by `shouldInterceptRequest`).
7. **`VIEW` intent filter is non-functional** — `intent.data`/`onNewIntent` are never handled, so opening http(s) links from other apps launches the default tab only.
8. **`POST_NOTIFICATIONS` never requested at runtime** (API 33+) — even if C1 were fixed, the notification would be dropped.
9. **`CastProtocol.CHROMECAST`** enum exists; only DLNA is implemented.
10. **Dead statements in `MainActivity.kt:131-134`** (discarded cast + manual `CastForegroundService()` instantiation) mark an abandoned refactor of the cast flow.

## 6. Test-suite weaknesses

- `WatchProgressLogicTest` **re-implements** the `positionMs >= durationMs * 0.95` formula and `URI(...).host` inline instead of calling `AppRepository.recordOrUpdateWatch` — it can never fail when production code breaks (false confidence; the whole feature it covers is dead per C1 anyway).
- No tests for the proxy's URL handling (where the proven corruption lives), `WebViewPool`, or discovery — despite README framing those as the risky parts.

## 7. What was validated

- ✅ `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, all tests pass.
- ✅ Double-decode bug (C4) **reproduced empirically** against the exact dependency jar; test harness cleaned up afterwards.
- ⚠️ The codebase-memory graph has no code nodes for this repo, so findings come from a complete direct read of `app/src` rather than graph traces; on-device TV/LAN behavior was not exercised.

## 8. Recommended fix order

1. Wire `startCasting` properly (or delete the service and move progress persistence into `DlnaCastController`).
2. Fix AndroidView tab identity: restore the active tab *before* first composition and add an `update {}` block.
3. Make `getOrCreateWebView` honor `initialUrl` when the cached view is stale (or always `loadUrl` on re-entry when the DB URL differs).
4. Remove the second `URLDecoder.decode` in `LocalStreamProxy.serve` (NanoHTTPD already decoded).
5. Own the proxy lifecycle: stop it when the cast stops, guard `start()` against port-in-use, validate the token (reject unknown), bind advertise-IP selection to the WLAN interface.
6. Persist tab titles on `onPageTitleChanged` and pass `positionMs` as `startMs` when resuming a cast.
