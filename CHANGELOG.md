# Changelog

## 1.1.1 — Save integrity hardening, regression tests & build verification
Added
- **Save Integrity Hardening**: `DocumentRepository.save` now stages UTF-8 writes to a temporary file (`plain_save_*.tmp`) and verifies length + SHA-256 digest before opening or touching destination files. Existing target files are backed up to `plain_bak_*.tmp`. If target streaming or read-back verification fails, original files are restored, unsaved drafts in `SessionStore` are retained, and success is never reported.
- **Regression Test Suite**: Added 12 new unit/scenario tests (total 215 passing tests) covering write failures, mid-write failures, verification digest/size failures, restoration failures, corrupted session/draft recovery, multibyte UTF-8 Unicode (emoji, CJK, accents, surrogate pairs), and line endings (LF, CRLF).
- **Gradle Build & Lint Verification**: Gradle build (`app:assembleDebug`), unit tests (`app:testDebugUnitTest`), and Android Lint (`app:lintDebug`) verified with 0 errors. Fixed Android 13 back callback `@TargetApi(33)` annotation guard and moved API-27+ `windowLightNavigationBar` theme items to `values-v27` resource qualifiers.

## 1.1.0 — tabs, large files, find/replace, indentation, PDF, Open with
Added
- **Large files up to 10 MiB** (was 1 MiB): the document lives in a gap-buffer `TextBuffer`; the editor shows a line-aligned window that slides when you scroll to its edge; Top/Bottom buttons, a position seek bar; file reading, verified streaming save, Replace All, match counting and draft writes run off the main thread; About lists timings measured on the device.
- **Up to 10 tabs** with independent text, cursor, scroll position, selection, save status and undo/redo history; tab strip, next/previous tab, close with Save/Discard/Cancel; memory budget of ~30 M characters across tabs.
- **Session restore:** all tabs (including unsaved text, cursors, scroll, PDF page/zoom) come back after the app is closed or killed; clean tabs are reloaded lazily from their files.
- **Undo history** per tab, kept across Activity recreation (process-wide workspace) and — for tabs with unsaved changes — across app restarts (bounded, checksum-validated, no redo). Replace All is one bounded entry.
- **Find and replace:** next/previous with wrap, match count and current index, match case, whole word, Replace, Replace All (one undo step), over the whole file.
- **Indentation:** Tab key/button, width 2/3/4/8, spaces or tab characters, multi-line indent/outdent, hardware Tab/Shift+Tab; YAML always uses spaces.
- **File types:** editable `.html`, `.htm`, `.json` in addition to `.md .txt .yml .yaml .py`.
- **PDF viewer** (view-only): page navigation, go-to-page, zoom −/+/fit, remembered per tab; PDFs are detected by content and never opened as text.
- **Open with:** VIEW/EDIT intent filters for the supported types (MIME and extension based, `content://`); opens in a new tab, read-only when no write access was granted, never disturbs existing tabs.
- One-time migration of an unsaved 1.0.0 draft into the first tab.
- 203 JVM tests (was 83), measured 10 MiB timings, static APK inspection.

Changed (behaviour)
- **New / Open / Recent now open a tab** instead of replacing the current document, so they no longer ask whether to discard. Closing a dirty tab asks Save/Discard/Cancel; the Back button with unsaved tabs now offers *Exit, keep drafts / Discard all / Cancel* (it previously offered Save/Discard/Cancel for the single document).
- Draft backups are written ~1.5 s after the *first* unsaved edit of a burst (previously: 1.5 s after the last keystroke) so continuous typing cannot postpone the backup indefinitely.
- Opening a file reuses an untouched blank tab.
- Saving streams the text instead of building one big byte array, and verifies with length + SHA-256 instead of a full byte comparison.
- Text limit raised from 1 MiB to 10 MiB; App versionCode 2, versionName 1.1.0.
- Package layout: `DraftStore` replaced by `SessionStore`; the single-document `EditorController` became a tab workspace controller.

Known gaps in this release: not run on a device or emulator; Gradle build not run (see docs/TESTING.md).

Defects found and fixed during development of 1.1.0
- Undo: Tab/indent/replace edits merged into neighbouring typing steps (found by tests).
- Indent: a selection starting at column 0 lost the new indent (found by tests).
- UI review: spurious window shift from a window swap's own scroll events; PDF page not re-fitted after rotation; v1.0 draft orphaned on upgrade; `OutOfMemoryError` bypassing `catch (Exception)`.

## 1.0.0 — initial version
- Raw text editor for `.md`, `.txt`, `.yml`/`.yaml`, `.py` (and any UTF-8 text file) using the system file picker; no permissions.
- New, Open, Recent files, Save (verified write), Save As, Export copy, File name for new documents.
- Draft backup and recovery; unsaved-changes dialogs on Back / New / Open / Recent.
- Strict UTF-8, BOM and CRLF preservation, 1 MiB open limit, refusal of binary / UTF-16 / invalid files.
- Undo/redo, symbol bar, word-wrap and monospace toggles, light/dark theme from the system.
- 83 JVM unit/scenario tests; scripted Gradle-free build producing a signed debug APK; Gradle files added (not run).

Defects found and fixed during development:
- Found by unit tests: typing after pressing Enter was merged into the Enter keystroke's undo step.
- Found by unit tests: a wrong test expectation about mixed line endings; the rule (strict majority wins, ties → LF) is now tested explicitly and documented.
- Found by code review (no test existed): after activity recreation (e.g. a system theme switch) a saved file would have shown as a blank document; it is now reopened from its URI.
- Found by code review: the editor could stay disabled after the "Opening…" phase; focus is now restored.
