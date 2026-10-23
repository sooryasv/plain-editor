# Testing Record — Version 1.1.1

## Overview of Test Results

Testing for Plain Editor v1.1.1 is divided into four distinct categories:

| Category | Status | Summary |
|---|---|---|
| **1. Automated JVM Unit Tests** | **PASS (215 / 215)** | Executed via `./gradlew testDebugUnitTest` and `tools/test.sh`. 0 failures, 0 skipped. |
| **2. Android Lint Analysis** | **PASS (0 errors)** | Executed via `./gradlew lintDebug`. All API level guards (`@TargetApi(33)`) and resource qualifiers (`values-v27`) verified. |
| **3. Build Integrity** | **PASS** | Debug build (`./gradlew assembleDebug`) succeeded. APK generated at `app/build/outputs/apk/debug/app-debug.apk`. |
| **4. Developer Manual Device Testing** | **PASS** | Developer-performed testing on **two physical Android phones** with **0 bugs encountered** across text editing, multi-tab operations, large files, Find/Replace, indentation, draft recovery, SAF file pickers, and PDF viewing. |

---

## 1. Automated JVM Unit Tests (215 Tests)

### Summary by Class
| Test Class | Tests | Test Class | Tests |
|---|---|---|---|
| `TabsTest` | 36 | `TextCodecTest` | 14 |
| `EditingOpsTest` | 27 | `LargeFileTest` | 12 |
| `EditorControllerTest` | 28 | `IndentToolsTest` | 12 |
| `DocumentRepositoryTest` | 22 | `TextSearchTest` | 9 |
| `UndoHistoryTest` | 8 | `UndoHistoryBulkAndPersistTest` | 6 |
| `TextBufferTest` | 7 | `CodecStreamingTest` | 6 |
| `FileNamingTest` | 6 | `WindowingTest` | 5 |
| `SessionStoreTest` | 5 | `PdfLogicTest` | 5 |
| `RecentFilesTest` | 4 | `LegacyMigrationTest` | 3 |

### Benchmark Measurements (Desktop JVM)
| Operation (10 MiB / 10,000,551-char Document) | Measured Execution Time |
|---|---|
| Open & decode UTF-8 into buffer | 25 ms |
| Jump to Top / Bottom / Position Bar Seek | < 1 ms |
| 500 keystrokes in window | 9 ms total |
| 50 window edge shifts | 6 ms total |
| Search scan (7 MB offset / full file scan) | 71 ms / 52 ms |
| Match count (180,556 matches) | 99 ms |
| Replace All (141,000 matches) incl. buffer swap | 127 ms |
| Single-step Undo of Replace All | 25 ms |
| Staged save + SHA-256 read-back verification | 169 ms |
| Draft pause-flush to session store / restore | 60 ms / 18 ms |

---

## 2. Developer Manual Physical Device Testing

The developer performed manual testing on **two physical Android smartphones** (Android 12 and Android 14) and confirmed that all core workflows functioned as intended with **0 bugs encountered**:

### Workflows Verified on Physical Devices:
1. **App Launch & Editing**: Launches immediately into a fresh tab with the soft keyboard raised. Text typing, cursor movement, selection, and clipboard operations function smoothly.
2. **Storage Access Framework (SAF)**: Creating new files, opening existing files, **Save**, **Save As...**, and **Export Copy...** successfully interface with Android's system file picker without requiring storage permissions.
3. **Multi-Tab Workspace**: Opening up to 10 tabs, switching between chips, closing clean and dirty tabs, and unsaved changes confirmation dialogs behave properly.
4. **Draft Recovery & Session Restore**: Backgrounding the app or force-stopping it while tabs have unsaved text writes draft files to app-private storage (`context.filesDir`). On relaunch, all tabs, text, cursors, and scroll states are restored automatically.
5. **Save Integrity**: If target write fails or provider returns incomplete data, the original file is restored from backup, the draft is retained in `SessionStore`, and status remains `Unsaved`.
6. **Large Files (10 MiB)**: Windowing sliding at top/bottom scroll edges, position bar seek, jump to top/bottom, and Replace All across 10 MB files operate without application freezes.
7. **Find & Replace**: Search wrapping, case matching, whole-word filtering, Replace, and Replace All execute correctly.
8. **Indentation**: Tab key/button, multi-line indent/outdent, and YAML space enforcement function properly.
9. **PDF Viewer**: View-only PDF opening, page navigation (`Prev`, `Next`, page jump), zoom controls (`Zoom In`, `Zoom Out`, `Fit`), and page/zoom restoration on tab switching execute smoothly.

---

## 3. Documented Limitations & Provider Edge Cases

- **Third-Party Storage Providers**: If a third-party or cloud storage provider fails during both the target write and backup restoration, the destination file state depends on the provider. However, Plain Editor returns `SaveResult.Failure`, keeps the unsaved draft in `SessionStore`, and displays an error dialog.
- **Large File Selection Bounds**: Select All, copy, and cut apply to the visible window (~120,000 characters) rather than the entire 10 MiB buffer.
- **PDF Viewer Scope**: View-only viewer built on `android.graphics.pdf.PdfRenderer`. Does not support pinch-zoom gestures, PDF text selection, PDF text search, or PDF editing.
