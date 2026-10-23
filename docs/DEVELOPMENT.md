# Development guide

Overview and quick start are in the [README](../README.md); results are in [TESTING.md](TESTING.md).

## Architecture
```
Process-wide:  AppState (ui) ── owns ──▶ EditorController (core)  ◀── survives the Activity
                                           │  tabs: List<Tab>   (TextBuffer + UndoHistory + cursor/scroll/status each)
MainActivity ──implements──▶ EditorView ◀──┤  window logic ─▶ Windowing         (which slice the EditText shows)
  EditText window, tab strip, find bar,    │  search       ─▶ TextSearch        (literal, case/whole-word)
  tool strip, dialogs, pickers, intents    │  indentation  ─▶ IndentTools       (pure edit plans)
PdfPane (PdfRenderer) shown for PDF tabs   ├─▶ DocumentRepository ─▶ DocumentStorage ◀─ ContentResolverStorage (ui)
                                           │       TextCodec: strict UTF-8, BOM, LF/CRLF, streaming encode, PDF sniff
                                           ├─▶ SessionStore (tab list + per-tab unsaved text + bounded undo history)
                                           ├─▶ RecentsStore ◀─ PrefsRecents (ui)
                                           └─▶ TaskRunner ◀─ AndroidTaskRunner (ui): 1 I/O thread + main handler
```
`core` must not import `android.*`; that is what makes the JVM tests possible. UI code stays thin: it forwards edits,
selection and scrolling to the controller and renders what the controller tells it.

### How a 10 MiB file is handled (the central design decision)
A stock `EditText` lays out its *entire* text, which is unusable at millions of characters. So:
- The document lives in a **`TextBuffer`** (gap buffer, 2 bytes/char). It is the single source of truth for saving, search, undo,
  drafts and window contents. Edits near the previous edit are O(edit size); a far-away edit moves the gap once (a memmove: ~0.4 ms for 10 MiB on a desktop JVM).
- The **`EditText` shows a window** `[winStart, winEnd)` of it: the whole document if ≤ `Limits.FULL_DOC_CHARS` (250,000), otherwise
  ~`WINDOW_CHARS` (120,000) chars aligned to line starts/ends (a line longer than `ALIGN_SCAN` = 20,000 is cut mid-line, so a
  5 MB minified-JSON line cannot defeat the window).
- **Every `EditText` change is mirrored into the buffer immediately** (`onWindowEdit`, window coordinates → document coordinates), with
  consistency checks (the removed text must match; the resulting window length must equal the editor's). If they ever disagree the
  controller trusts the editor, resynchronises, resets undo and tells the user — it never silently diverges.
- **Scrolling to the window's top/bottom edge** calls `requestShift(±1)`: the window moves by `WINDOW_STEP` (40,000) and the visible
  line stays put (the UI scrolls to the anchor line after layout). The Activity rate-limits edge triggers (400 ms) and suppresses them after any window swap.
- **Jump top/bottom/seek/find/undo** re-window around the target offset (`Windowing.around/top/bottom`).
- Window, step and alignment constants are **not tuned on a device**; they are in `Limits` and the numbers to tune them with appear in About ▸ Performance.
- Background work: reading/decoding a file, streaming + verifying a save, Replace All (on a snapshot), match counting (on a snapshot) and draft writes run on the I/O thread. The only main-thread O(n) steps are `copyChars()` snapshots (~10 ms per 10 MiB on a desktop JVM) and `setText` of one window.

### Other decisions
- **Tabs:** `Tab` objects live in `EditorController`; text tabs hold a `TextBuffer` (null until loaded after a restart), PDF tabs only uri/page/zoom. `AppState` keeps the controller alive when the Activity is recreated; `attach()/detach(view)` swap the view. An untouched blank tab is reused when a file is opened. New/Open never replace a document, so they need no discard prompt; only closing a dirty tab and leaving the app prompt.
- **Memory budget:** `MAX_TOTAL_CHARS` = 30 M chars across tabs (checked on open); `OutOfMemoryError` from open/save/restore/snapshots is caught and reported, not crashed on.
- **Honest & Safe save:** `DocumentRepository.save` validates text (unpaired surrogates) *before* opening output. It stages the UTF-8 write to a temporary file (`plain_save_*.tmp`) and verifies its SHA-256 digest before opening the target file stream. Original target files are backed up to `plain_bak_*.tmp`. The verified temp file is streamed to the target URI and verified via length and SHA-256 readback. If write or verification fails, the original file is restored from backup, the unsaved draft in `SessionStore` is retained, and success is never reported. CRLF expansion happens while streaming; an existing `\r\n` is not doubled.
- **Dirty tracking** is a per-tab version counter. A save marks a tab clean only if its version equals the snapshot's; undo back to the saved text still shows *Unsaved* (conservative).
- **Undo (`UndoHistory`)**: entries in document coordinates; `Edit` (start, removed, inserted) with typing/backspace merging (≤1 s apart, ≤200 chars, no `\n`, only `mergeable` entries — Tab/indent/replace are never merged) and `Bulk` (Replace All: starts[], removed[], replacement) so a million matches is one entry. Bounded by 4 M chars; a single oversized bulk entry clears history instead of being kept. Applying an entry first verifies the buffer matches (Bulk: every range) — otherwise history is dropped. `serialize()/deserialize()` keep the newest ≤1 M chars of undo steps; restored entries get an "infinitely old" timestamp so new typing never merges into them.
- **Session persistence (`SessionStore`)**: `session.meta` (tab list, active tab, per-tab uri/name/line ending/BOM/read-only/cursor/scroll/PDF page+zoom), `tab-N.text` (raw UTF-16 code units; exists **only while tab N has unsaved changes** → "text file exists ⇒ restore as unsaved"), `tab-N.hist` (CRC32 of the text it belongs to + serialised history; ignored on mismatch). All writes are temp-file + fsync + rename; each tab has an epoch bumped by `clearTab` so a slow background write that started earlier cannot resurrect a cleared draft. Clean tabs reload lazily from their URI when first selected; a failed reload closes the tab with a message (nothing unsaved is involved). `LegacyDraftMigration` turns a v1.0 single draft into tab 1.
- **Find/replace** works on the `TextBuffer`, not the EditText; matches outside the window re-window. Highlight is a `BackgroundColorSpan` (so the match stays visible while the find field has focus). Replace All is planned on a snapshot (`TextSearch.planReplaceAll`), applied only if the tab's version is unchanged, via `TextBuffer.assign`.
- **Indentation** is a pure function from (text, selection, settings) to an `EditPlan`; the controller applies it as one non-mergeable edit.
- **PDF:** `PdfSniffer` (`%PDF-` in the first 1024 bytes) is checked **before** the size limit and before any text decoding; a `.pdf` name or `application/pdf` type without that header is refused (never shown as text). `PdfPane` renders one page at a time with `PdfRenderer` on a single thread, bitmap size from `PdfMath.bitmapSize` (≤ 8 M pixels), falls back to a cache-file copy (≤ 300 MB) when the provider's descriptor isn't seekable.
- **Open with:** two intent filters (VIEW/EDIT, `content://`): by MIME type, and `*/*` + `.*\.ext` path patterns (needs `host="*"`). `launchMode=singleTask` so one instance receives `onNewIntent`. Write access = the intent's `FLAG_GRANT_WRITE_URI_PERMISSION` or a held write grant; otherwise the tab is read-only. Persistable grants are requested best-effort.
- **Config changes:** rotation etc. are handled in place (`configChanges`); a theme change recreates the Activity, and the workspace (including undo) is simply re-attached.
- **Back handling:** `OnBackInvokedCallback` on Android 13+, `onBackPressed` before. `minSdk 26` because the dex tool used in the verified pipeline (`dx`) only accepts `invokedynamic` from API 26.

## Environment setup
**Normal:** Android Studio (Koala or newer) ▸ open the folder ▸ sync ▸ run. CLI: README Option A.
**Gradle-free pipeline:** `tools/build_offline.sh`: `aapt2 compile/link` → `kotlinc` (`-jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class`) → `dx` over app classes + Kotlin stdlib → `zipalign` → `apksigner`. It injects `package=` into a copy of the manifest because AGP 8 forbids it in the source manifest.

## Tests
`KOTLINC=… bash tools/test.sh` compiles `core/*.kt` + `src/test` and runs JUnitCore (heap 1.5 GB). Conventions:
- Put logic in `core`; test it with `Env` (controller + `FakeView` + `FakeStorage` + `TestRunner` + real `SessionStore` in a temp dir) from `Fakes.kt`.
- `FakeView` simulates an EditText window (`type`, `typeAt`, `deleteRange`, `select`, `replaceAll`); `Env.assertInSync()` asserts the editor window equals the model's expected window — call it after every window operation in new tests.
- Simulate "app restart" by creating a second `Env` over the same directory and storage; simulate "Activity recreation" with `detach(view)` / `attach(newView)`.
- `TestRunner(deferIo = true)` queues background work so races (edit during save, edit during Replace All, stale counts) can be scripted.
- UI behaviour needs a device: follow the checklist in TESTING.md.

## Debugging
- `adb logcat` (the app logs nothing itself). About ▸ Performance shows timings for open, window layout, window shift, jump, find, count, Replace All, save+verify, draft write and PDF page render.
- Session files live in `files/session/` (`session.meta`, `tab-N.text` = UTF-16BE code units, `tab-N.hist`). With a Gradle *debug* build: `adb shell run-as app.plaintext.editor ls files/session`. The offline-script APK is not marked debuggable, so `run-as` won't work on it.
- To tune large-file feel: change `Limits.WINDOW_CHARS` / `WINDOW_STEP` / `FULL_DOC_CHARS` and compare the "window layout" and "shift window" timings in About.

## Ideas not implemented
Go to line / line numbers; regular-expression search; pinch-zoom, tiled rendering, text search and password support in the PDF viewer; whole-file Select All in large-file mode; persisting redo and the history of saved tabs; instrumented tests (Espresso/UI Automator) and Robolectric; `file://` links; Activity Result API (needs AndroidX); R8 shrinking; release signing.
