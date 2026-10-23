# Plain Editor

A small, fast, offline Android plain-text editor and PDF viewer — `.md`, `.txt`, `.yml` / `.yaml`, `.py`, `.html` / `.htm`, `.json` — with multi-tab support, find & replace, indentation tools, large-file windowing (up to 10 MiB), and automatic draft recovery. Maintained by **Vaixus Technologies**.

> **Verification status — Version 1.1.1**
> - **Automated Logic Tests**: **215 passing JVM unit and scenario tests** (0 failures).
> - **Build Integrity & Lint**: Clean Gradle build (`./gradlew assembleDebug`) and Android Lint (`./gradlew lintDebug`) with **0 errors**.
> - **Developer Manual Device Testing**: Manually tested on **two physical Android phones** with **0 bugs encountered** across text editing, multi-tab switching, large files, Find/Replace, indentation, draft recovery, SAF file pickers, and PDF viewing.

---

## What It Does

- **Instant Start**: Opens directly into an empty editor with the soft keyboard ready — launch and type.
- **Up to 10 Tabs**: Each tab maintains independent text, cursor position, scroll state, save status, and undo/redo history. Unsaved changes survive tab switching and closing (closing asks first).
- **Large-File Support (up to 10 MiB)**: Text files up to **10 MiB** (UTF-8). Uses a memory-efficient gap buffer (`TextBuffer`) and line-aligned editor windowing (~120,000 chars visible at a time). Jump top/bottom, position seek bar, find, and Replace All work seamlessly across the full document.
- **Supported File Types**: Editable `.md`, `.txt`, `.yml`, `.yaml`, `.py`, `.html`, `.htm`, `.json`. View-only `.pdf` documents open in a separate viewer with page navigation and zoom controls.
- **Find & Replace**: Next/previous search with wrap, match counter, case sensitivity toggle, whole-word matching, Replace, and whole-file Replace All (single undo step).
- **Indentation Tools**: Tab key/button, customizable width (2/3/4/8 spaces or tab characters), multi-line indent/outdent. YAML files automatically enforce spaces.
- **System File Integration**: Opens, saves, exports copies, and handles "Open with..." (`content://` links) via Android's Storage Access Framework (SAF) without requiring storage permissions.
- **Save Integrity & Draft Recovery**: Staged temporary writes with SHA-256 digest checks, original file backup creation, read-back verification, and automatic draft backups (~1.5s after editing) that restore full sessions after process restarts.
- **Customizable UI**: Undo/redo, word-wrap toggle, monospace font toggle, symbol toolbar, recent files list, and system light/dark theme adaptation.

---

## What It Deliberately Does Not Do

No cloud accounts, no network access, no analytics, no AI features, no Markdown/HTML live preview, no syntax highlighting, no PDF editing or text selection/search, no code execution, and no regular expression search.

---

## Visual Documentation

*Note: Screenshots captured from physical device testing will be uploaded to this section for the GitHub release.*

---

## Technical Architecture

- **Language & Platform**: Modern Kotlin built directly on the Android platform framework (`EditText`, `AlertDialog`, `PdfRenderer`, `Storage Access Framework`).
- **Dependencies**: minSdk 26 (Android 8.0), targetSdk 34. **Zero third-party runtime dependencies, zero AndroidX/Compose overhead** — relies exclusively on the Kotlin standard library and core Android platform APIs.
- **Decoupled Architecture**: All core document logic, windowing, search, indentation, history, and persistence live in an Android-free `core` package tested directly on the JVM. A lightweight `ui` package connects the core model to Android Views. Details in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Privacy & Security

- **Zero Permissions Declared**: The app declares **no permissions** in `AndroidManifest.xml` (no `INTERNET`, no storage permissions).
- **Complete Data Privacy**: No telemetry, no analytics, no ads, and no external connections. Reads and writes only files explicitly selected via the system file picker or passed via "Open with...".
- **Local Storage Only**: Draft backups, session metadata, and undo histories are stored strictly in app-private local storage (`context.filesDir`). `allowBackup` is disabled (`false`).

---

## Building & Installation

### Prerequisites
- JDK 17 or newer.
- Android SDK with API 34 platform tools installed.
- Gradle 8.7 (wrapper included).

### Building via Gradle
```bash
# 1. Configure local SDK path
echo "sdk.dir=$HOME/AppData/Local/Android/Sdk" > local.properties

# 2. Run unit tests
./gradlew testDebugUnitTest

# 3. Run Android Lint
./gradlew lintDebug

# 4. Build Debug APK
./gradlew assembleDebug
# APK Location: app/build/outputs/apk/debug/app-debug.apk
```

### Installing on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or copy `app-debug.apk` directly to your Android device and install via your file manager ("Install unknown apps").

---

## Test Verification Summary

| Test Category | Results & Scope |
|---|---|
| **Automated Unit Tests** | **215 passed, 0 failed** (`./gradlew testDebugUnitTest`) — covers gap buffer, windowing, codecs, search, indentation, save-integrity staging, backup restoration, session store, PDF math, and undo history. |
| **Android Lint** | **0 errors** (`./gradlew lintDebug`) — verified API level annotations (`@TargetApi(33)`) and resource qualifiers (`values-v27`). |
| **Build Integrity** | **Debug APK built successfully** (`app/build/outputs/apk/debug/app-debug.apk`). |
| **Manual Device Testing** | **Developer-tested on 2 physical Android devices** — verified editing, tabs, large files, Find/Replace, indentation, draft recovery, SAF file pickers, and PDF viewing with **0 bugs encountered**. |

See [docs/TESTING.md](docs/TESTING.md) for detailed performance metrics and manual device checklists.

---

## Known Limitations

- **Large File Selection**: Whole-file "Select All" and text selection cover the visible line-aligned window (~120,000 chars) rather than the entire 10 MiB buffer.
- **PDF Viewer**: Read-only page navigation, zoom in/out/fit controls, and page jumps. Does not support pinch-zoom, PDF editing, text selection, or text search.
- **Storage Provider Fallbacks**: If an external cloud or third-party storage provider fails during in-place saves or backup restoration, the app retains the draft in `SessionStore`, displays a failure dialog, and never reports success.

---

## License & Contributing

- **License**: Released under the [MIT License](LICENSE) (Copyright © 2026 Soorya S V).
- **Maintainer**: Maintained by **Vaixus Technologies**.
- **Contributing**: Issues, feature discussions, and pull requests are welcome on GitHub. Please see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for architectural guidelines and testing instructions.
