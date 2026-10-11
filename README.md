# CastBrowser

An Android browser that watches video for you: open a streaming site in a multi-tab WebView, let the app detect the MP4/HLS/DASH stream, and cast it to a TV on the same Wi-Fi in one tap — with tabs and watch history that survive app close and reboot.

No account, no backend, no bundled browser engine. The phone does all the work; the TV only needs to fetch a URL.

## Tech stack

Read from `build.gradle.kts`, `app/build.gradle.kts`, and `gradle/wrapper/gradle-wrapper.properties`:

| Concern | Tooling |
| --- | --- |
| Language | Kotlin 2.1.10, JVM target 17 |
| Build | Gradle 8.12, Android Gradle Plugin 8.8.2, version catalog-free `settings.gradle.kts` |
| UI | Jetpack Compose (BOM `2025.02.00`), Material 3, Navigation Compose 2.8.7 |
| Persistence | Room 2.6.1 (via KSP 2.1.10-1.0.29), DataStore Preferences 1.1.2 |
| Web | AndroidX WebKit 1.12.1 (document-start script injection), system WebView |
| Networking | OkHttp 4.12.0, NanoHTTPD 2.3.1 (embedded local proxy) |
| Casting | Hand-rolled DLNA/UPnP (SSDP + SOAP) — see `app/src/main/java/com/castbrowser/app/cast/` |
| Async | Coroutines/Flow 1.10.1, kotlinx-serialization-json 1.7.3 |
| Media | androidx.media 1.7.0 (MediaSession/notification controls) |
| Tests | JUnit 4.13.2, Truth 1.4.4, coroutines-test, Room testing |

Not used, and deliberately so: Dagger/Hilt (manual `AppContainer` DI), GeckoView (APK size), and any server or cloud sync.

## Project structure

```
app/                        Single Android application module (no library modules)
  src/main/java/com/castbrowser/app/
    browser/                WebView pool (LRU, max 3 live), ad/popup blocker, tab state save/restore
    detect/                 Stream classification (MP4/HLS/DASH), OkHttp probe, DRM detection
    proxy/                  NanoHTTPD local proxy + HLS playlist rewriter
    cast/                   DLNA discovery (SSDP), SOAP controller, foreground playback service
    data/                   Room database, DAOs, entities, repository, settings, episode title parser
    ui/                     Compose screens (browser, tabs, history, home), components, theme
  src/main/assets/          video_detector.js — JS injected into pages to harvest media URLs
  src/test/java/            JVM unit tests
  build.gradle.kts          SDK levels, build types, dependency versions
  proguard-rules.pro        R8 keep rules (nanohttpd)
docs/plans/                 Phased implementation plan
.specs/                     Product and technical spec (the source of truth for design decisions)
gradle/wrapper/             Gradle 8.12 wrapper
```

## Prerequisites

- **JDK 17** (Temurin 17 verified working) — `sourceCompatibility`/`targetCompatibility` are `VERSION_17`, `jvmTarget = "17"`
- **Android Studio** with **Android SDK Platform 35** (`compileSdk = 35`) and **Build-Tools**
- **Android SDK Platform 26+** for running (`minSdk = 26`; Android 8.0 Oreo)
- A physical device or emulator on **API 26+**. Casting needs real LAN hardware — DLNA discovery will not find a TV from the standard emulator unless you bridge networking.

## Setup

```bash
# 1. Point the build at your SDK (local.properties is machine-specific and should not be committed)
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2. Generate the Gradle wrapper and fetch dependencies
./gradlew --version
```

Then build:

```bash
./gradlew assembleDebug
```

## Environment variables

None. The app has no `.env`, no `BuildConfig` fields, and no `System.getenv` calls. The only local configuration is `local.properties`, which holds a single key:

| Key | Description |
| --- | --- |
| `sdk.dir` | Absolute path to your Android SDK installation |

## Running the app

```bash
# Build and install on a connected device/emulator
./gradlew installDebug

# Or build the APK and install manually
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.castbrowser.app/.MainActivity
```

Alternatively, open the project in Android Studio and press Run.

Entry point is `MainActivity` (`app/src/main/java/com/castbrowser/app/MainActivity.kt`): it hosts the Compose `NavHost` over routes `browser`, `tabs`, `history`, and `home`, pulls dependencies from `AppContainer` on `CastApplication`, and starts playback through the embedded proxy when a detected stream needs one.

## Running tests

Six JVM unit test classes exist under `app/src/test/java/com/castbrowser/app/`, covering the pure-logic parts that are testable without a device:

`AdBlockEngineTest`, `DlnaXmlHelperTest`, `EpisodeTitleParserTest`, `WatchProgressLogicTest`, `StreamClassifierTest`, `HlsPlaylistRewriterTest`

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# HTML report
open app/build/reports/tests/testDebugUnitTest/index.html

# Lint
./gradlew lint
```

There is currently no instrumentation test source set, despite `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` being configured.

<!-- TODO: the implementation plan (Task 2, 5, 6, 8) calls for DAO, proxy, tab-state-manager and stream-probe tests that do not exist yet. -->

## Deployment

**There is no CI/CD.** No `.github/workflows`, no `.gitlab-ci.yml`, and no release automation — the repository is not even under version control.

Deployment is manual. The intended path, per `docs/plans/`:

```bash
./gradlew assembleRelease
# TODO: the plan's Task 11 describes a Telegram upload script using TELEGRAM_TOKEN and
# TELEGRAM_CHAT_ID. That script does not exist in the repo yet, so there is currently
# no upload step to run.
```

⚠️ **Release signing is not configured.** The `release` build type in `app/build.gradle.kts` sets `signingConfig = signingConfigs.getByName("debug")`, so a release APK is signed with the throwaway debug key and cannot be published. A real keystore and `signingConfigs.release` block must be added before any distribution. R8 full mode and resource shrinking are already enabled, targeting under 15 MB (spec target: 6–12 MB).

Cleartext HTTP is permitted globally via `app/src/main/res/xml/network_security_config.xml` — required so the phone can serve the local proxy and talk to DLNA TVs over plain HTTP. TODO: tighten this to private-network domains only before shipping.

## Design notes

`.specs/CastBrowser Android Video Cast App, Tech Spec.md` is the authoritative design document, and `docs/plans/2026-10-10-cast-browser-full-implementation.md` is the 11-task build plan it maps to. Read the spec before changing behavior — the roadmap, feature priorities (P1/P2/P3), explicit non-goals (DRM, mirroring, AirPlay/Miracast, downloads, accounts), and the 6–12 MB size budget are all decisions recorded there, not incidental choices.
