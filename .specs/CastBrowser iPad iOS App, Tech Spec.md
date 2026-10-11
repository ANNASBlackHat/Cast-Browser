# CastBrowser iPad & iOS App: Technical Specification & Architecture

Oct 11, 2026 · @Annas

---

## 1. Overview & Vision

**CastBrowser for iPad & iOS** is a web browser built for media playback and TV casting with persistent memory, tailored specifically for iPadOS (supporting Stage Manager, split screen, and Apple Pencil/touch) and iOS.

Like the Android version, CastBrowser for iOS solves the problem of stream discovery and watch persistence:
1. Browse web pages with full multi-tab persistence across app closures and system reboots.
2. Automatically sniff and extract media streams (HLS `.m3u8`, MP4, DASH `.mpd`).
3. Cast seamlessly to televisions and external displays using **AirPlay first** (zero setup on Apple TV, Roku, modern Samsung, LG, Sony) and **DLNA/UPnP second** (for legacy smart TVs).
4. Serve protected or referrer-locked streams using an embedded local HTTP proxy server.
5. Track watch progress, season/episode numbers, and continue watching states locally with **SwiftData / SQLite**.

---

## 2. Key Differences from Android Implementation

| Area | Android Implementation | iPad & iOS (Swift) Implementation |
| :--- | :--- | :--- |
| **Language & UI** | Kotlin + Jetpack Compose | **Swift 6 + SwiftUI** |
| **Browser Engine** | Android `WebView` | **`WKWebView`** (WebKit engine) |
| **Script Injection & Hooks** | `addDocumentStartJavaScript` + JS Interface | **`WKUserScript`** (`.atDocumentStart`) + **`WKScriptMessageHandler`** |
| **Network Interception** | `shouldInterceptRequest` | **`WKURLSchemeHandler`** + In-Page JS Interceptor |
| **Casting Layer** | DLNA (SSDP) first, Cast SDK second | **AirPlay (`AVRoutePickerView`) first**, Native DLNA second, Google Cast third |
| **Local Proxy Server** | NanoHTTPD (`ServerSocket`) | **GCDWebServer** or minimal **SwiftNIO** server |
| **Local Network Discovery** | `DatagramSocket` + `MulticastLock` | Apple **`Network.framework` (`NWBrowser` / `NWConnectionGroup`)** |
| **Permissions** | `CHANGE_WIFI_MULTICAST_STATE` | **`NSLocalNetworkUsageDescription`** |
| **Background Playback** | Foreground Service (`mediaPlayback`) | **`AVAudioSession`** + `UIBackgroundModes: ["audio"]` |
| **Storage & Persistence** | Room (SQLite) + DataStore | **SwiftData** (or GRDB / SQLite) |
| **iPad UX** | Tablet layout in Compose | **`NavigationSplitView`**, Stage Manager, Tab Sidebar/Pills |

---

## 3. Core Architecture

The iOS app is designed cleanly into six modular layers:

```
┌────────────────────────────────────────────────────────┐
│                        SwiftUI UI                      │
│   (BrowserView, TabGrid, ContinueWatching, RemoteSheet)│
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────┴─────────────────────────────┐
│                     AppModel / Store                   │
│   (Observable Object / Swift 6 @Observable State)      │
└──────────┬──────────────────────┬──────────────────────┘
           │                      │
┌──────────▼───────────┐  ┌───────▼────────────┐  ┌──────▼─────────────┐
│     Browser Engine   │  │   Detection Engine │  │    Casting Engine  │
│  - WKWebView Pool    │  │  - WKUserScript    │  │  - AirPlay (AVKit) │
│  - State persistence │  │  - ScriptHandler   │  │  - DLNA (NWBrowser)│
│  - Ad/Popup blocker  │  │  - StreamProbe     │  │  - Media Remote    │
└──────────┬───────────┘  └───────┬────────────┘  └──────┬─────────────┘
           │                      │                      │
┌──────────▼──────────────────────▼──────────────────────▼─────────────┐
│                    Local Proxy & Data Layer                          │
│  - Embedded HTTP Proxy (GCDWebServer / SwiftNIO)                     │
│  - SwiftData Storage (Tabs, History, WatchProgress, Bookmarks)       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. Technical Specifications by Subsystem

### 4.1. Browser Engine & Multi-Tab State
- **View Container:** `UIViewRepresentable` wrapping `WKWebView`.
- **LRU Tab Pool:** Retains up to 3–4 live `WKWebView` instances.
- **State Serialization:**
  - `WKWebView` interaction history: `WKBackForwardList` serialized URLs and page scroll offsets.
  - Tab metadata saved to disk in `Application Support/Tabs/<id>.json`.
  - On launch, restores tab array without opening empty default tabs.
- **Ad & Popup Blocker:**
  - Implements `WKContentRuleListStore` for compiling Apple Safari native content-blocking rules (faster and uses zero CPU compared to runtime URL matching).
  - Implements `WKUIDelegate`:
    ```swift
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // Block target="_blank" and popup windows triggered by ad clicks
        return nil
    }
    ```

### 4.2. Stream Detection Engine
Because WebKit restricts `shouldInterceptRequest` on standard `https://` schemes, detection uses a dual-signal pipeline:
1. **Injected User Script (`WKUserScript`):**
   - Injected at `.atDocumentStart` across all frames (`forMainFrameOnly: false`).
   - Hooks `window.fetch`, `XMLHttpRequest.prototype.open`, `HTMLMediaElement.prototype.src`, and uses `MutationObserver` on `<video>` / `<source>`.
   - Sends stream URLs to Swift via `window.webkit.messageHandlers.castBrowserBridge.postMessage(...)`.
2. **Swift Stream Prober:**
   - Evaluates stream candidates via `URLSession` (`Range: bytes=0-1` or `HEAD`).
   - Detects HLS master playlists, MP4, and DASH streams.
   - Categorizes streams as Direct or Needs-Proxy.

### 4.3. Casting Engine (AirPlay + DLNA)
1. **AirPlay (Primary):**
   - Utilizes `AVPlayer` and `AVRoutePickerView`.
   - Apple automatically manages discovery for Apple TVs, AirPlay 2-compatible TVs (Samsung, LG, Sony, Roku).
   - Remote controls and lock-screen controls integrate natively via `MPRemoteCommandCenter` and `MPNowPlayingInfoCenter`.
2. **DLNA / UPnP (Secondary):**
   - Uses Apple's modern `Network.framework` (`NWConnectionGroup`) to multicast `M-SEARCH` packets to `239.255.255.250:1900`.
   - SOAP commands (`SetAVTransportURI`, `Play`, `Pause`, `Seek`, `Stop`) sent over `URLSession`.
   - Avoids third-party socket libraries for maximum iOS power efficiency.

### 4.4. Embedded Local Streaming Proxy
- **Engine:** GCDWebServer or minimal SwiftNIO embedded HTTP server.
- **Port:** Local ephemeral port (e.g., `8888` or dynamic).
- **Capabilities:**
  - Upstream request header forwarding (`Referer`, `User-Agent`, cookies from `WKHTTPCookieStore`).
  - Byte-range request support (`206 Partial Content`) for seeking.
  - HLS `.m3u8` playlist rewriter converting internal segment URIs into local proxy URLs.

### 4.5. Data Layer (SwiftData / SQLite)
- **Entities:**
  - `Tab`: `id`, `url`, `title`, `position`, `isActive`, `lastAccessedAt`.
  - `HistoryVisit`: `id`, `url`, `title`, `host`, `visitedAt`.
  - `WatchEntry`: `id`, `pageUrl`, `title`, `host`, `seriesKey`, `season`, `episode`, `positionSeconds`, `durationSeconds`, `finished`, `lastWatchedAt`.
  - `Bookmark`: `id`, `url`, `title`, `createdAt`.

---

## 5. iPadOS Specific Features

1. **Adaptive Navigation:**
   - **Compact (iPhone / iPad Slide Over):** Bottom bar with tabs, history, and URL input.
   - **Expanded (iPad Fullscreen / Split View):** Top tab strip like Safari / desktop browser with tab counts and quick closure.
2. **Stage Manager & Multitasking:**
   - Allows keeping video playing in a window while doing work in adjacent windows.
3. **Picture-in-Picture (PiP):**
   - Integrated `AVPictureInPictureController` for local preview alongside external casting.

---

## 6. Required Apple Permissions & Capabilities

1. **`NSLocalNetworkUsageDescription`:**
   - Required for discovering DLNA TVs and running the local proxy on Wi-Fi.
   - Message: *"CastBrowser needs access to your local network to discover Smart TVs and stream video directly to your television."*
2. **Background Modes:**
   - `Audio, AirPlay, and Picture in Picture` in `UIBackgroundModes` to allow continuous casting when iPad is locked.
3. **App Transport Security (ATS):**
   - `NSAllowsLocalNetworking: YES` to allow HTTP traffic strictly over private LAN IP addresses for legacy DLNA TVs.

---

## 7. Recommended Technology Stack for iOS/iPadOS

| Component | Choice | Reason |
| :--- | :--- | :--- |
| **Language** | Swift 6 | Native speed, memory safety, modern concurrency (`async`/`await`). |
| **UI Framework** | SwiftUI | Native iPadOS and iOS adaptive layout, minimal code footprint. |
| **Web Engine** | `WebKit` (`WKWebView`) | Built-in Apple engine, zero binary size overhead. |
| **Storage** | SwiftData | Native Apple ORM, seamless SwiftUI integration. |
| **Local Proxy** | `GCDWebServer` | Lightweight, battle-tested on iOS for video streaming and ranges. |
| **Networking** | `URLSession` + `Network.framework` | Built-in Apple networking for HTTP and SSDP UDP sockets. |
| **Now Playing** | `MediaPlayer` (`MPNowPlayingInfoCenter`) | Native lock-screen controls, volume controls, and Control Center sync. |

---

## 8. Phased Implementation Roadmap

- **Phase 1: Project Scaffolding & Browser Foundation**
  - Xcode project setup targeting iOS 17.0+ & iPadOS 17.0+.
  - `WKWebView` wrapper with URL bar, tab count badge, and SwiftData storage.
  - History tracking and tab state restore.
- **Phase 2: Stream Detection & Content Blocking**
  - Injected script for video detection across frames.
  - Native content blocking rules (`WKContentRuleListStore`) for ad and popup prevention.
  - Video candidate sheet UI.
- **Phase 3: Casting Layer (AirPlay & DLNA)**
  - Native AirPlay integration via `AVRoutePickerView`.
  - `NWConnectionGroup` DLNA SSDP discovery engine and SOAP AVTransport controller.
- **Phase 4: Embedded Proxy Server**
  - GCDWebServer setup with Range request forwarding and HLS rewriter.
- **Phase 5: iPad Polish & Remote Controls**
  - Floating remote control sheet and top-bar remote trigger.
  - iPad Stage Manager and Split View testing.
