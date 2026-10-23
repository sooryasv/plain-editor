# User guide

Installation: see the [README](../README.md#installing-on-device). This guide describes the app (version 1.1.1).

## The screen
From top to bottom:
- **Action bar:** file name (prefixed with `•` while unsaved) and a line such as `Markdown · LF · Saved`. Icons: **Undo**, **Redo**, **Find**, **Save** (some may move into the ⋮ menu on narrow screens).
- **Tab strip:** one chip per open document, with a `×` to close it and a `+` to open a new tab. `•` in a chip means unsaved changes. Up to 10 tabs. Tap a chip to switch; the strip scrolls sideways.
- **Find bar** (hidden until you use Find): search box, **Prev / Next / Close**, *Match case*, *Whole word*, the match counter, and — for Find and replace — a replace box with **Replace** and **All**.
- **Editor:** plain text; nothing is auto-formatted. Or, for PDFs, the PDF viewer.
- **Position bar** (only for big files): a slider showing where you are in the whole file; drag it to jump.
- **Tool strip** above the keyboard (scrolls sideways): **Top**, **Bottom**, **Tab**, **Outdent**, **Indent**, then `# - _ * ` ~ / \ {} [] () : = <> ""`. For the bracket/quote pairs: with text selected the pair wraps it, otherwise it is inserted with the cursor between. Hide this strip in ⋮ ▸ **Symbol bar**.

## Status line
| Shown | Meaning |
|---|---|
| New, not saved | Blank document with no file yet |
| Unsaved | Text differs from the file (or there is no file) |
| Unsaved (draft backed up) | Same, and a private backup of the current text has been written |
| Saving… | A save is running; nothing counts as saved until it finishes **and** is verified |
| Saved | The file was written and read back identically |
| Opening… | A tab is being restored/loaded; typing is paused |
| `PDF · view only` | A PDF tab |
| `· read-only` | The provider says the file cannot be written; Save will ask for a new location |

If a save fails you get an error box, the status stays *Unsaved*, and your text stays in the editor and the draft.

## Tabs
- **New tab:** `+` in the strip, or ⋮ ▸ **New tab**. Opening a file while the only tab is an untouched blank one reuses that tab.
- **Open a file:** ⋮ ▸ **Open…** (system file screen) or ⋮ ▸ **Recent files…**. It opens in a **new tab**; your other tabs and their unsaved changes are never touched. Opening a file that is already open simply switches to its tab.
- **Switch:** tap a chip, or ⋮ ▸ **Next tab / Previous tab**. Each tab remembers its cursor, scroll position, selection, save status and undo history.
- **Close:** `×` on the chip or ⋮ ▸ **Close tab**. If the tab has unsaved changes you are asked *Save / Discard / Cancel*. Closing the last tab leaves a fresh blank one.
- **Limits:** 10 tabs; about 30 million characters in total. Otherwise you are told and nothing changes.
- **Leaving the app (Back button):** if any tab is unsaved you see the list and choose **Exit, keep drafts** (everything comes back next time), **Discard all**, or **Cancel**. The Home button never asks; drafts are written automatically.

## Saving
- **Save** (disk icon): writes to the tab's file; for a new tab it first asks where.
- **Save As…** saves under a new name/place and the tab continues on the new file. **Export copy…** writes a copy and leaves the tab as it was.
- **File name…** (new tabs only): choose a name and tap `.txt .md .yml .py .html .json` to switch the extension. You can still change it in the system save screen. An extension you type is kept as typed and never doubled.
- PDF tabs cannot be saved or edited.
- If a write fails midway the file on disk may be incomplete, but your text is still in the editor — use **Save As…** to somewhere else.

## Large files (up to 10 MiB)
Files over about 250,000 characters are shown in **windows** so scrolling and typing stay responsive. What you see and what changes:
- The **Position bar** appears. Drag it to jump anywhere; **Top** and **Bottom** jump to the ends of the whole file.
- Scrolling normally works inside the window; when you reach its top or bottom edge the window slides along, so the next screenful appears (you may notice a brief pause there).
- **Select All, copy, cut and text selection apply to the window you are looking at, not the whole file.** For whole-file operations use Find/Replace.
- If you scroll far from the caret, the caret moves to the top of the visible text when the window slides, so typing happens where you are looking.
- Find, Replace and Replace All search the **whole** file, not just the window; Find takes you to the match.
- ⋮ ▸ **About & privacy** lists timings measured on your phone for opening, window changes, saving and searching.

## Find and replace
Tap the magnifier (or ⋮ ▸ **Find and replace**). If text is selected on one line, it is used as the search text.
- **Next / Prev** (or Enter on the keyboard) select the next/previous match and wrap around at the ends ("Wrapped around"). The counter shows `3 / 120` (or `1000000+` for huge counts).
- **Match case** and **Whole word** change what matches. Whole word applies where the search text begins/ends with a letter, digit or `_`.
- **Replace** replaces the selected match, then moves to the next. If the selection isn't a match, it just finds the next one. **All** replaces every match in the whole file in **one undoable step** (a note says how many). Very many matches (over 2 million) are refused.
- The search is literal text (no regular expressions) and case folding is per character.
- **Back** closes the find bar first.

## Indentation
- **Tab** (button or hardware Tab key) inserts one indent unit at the cursor (spaces up to the next tab stop, or a tab character). With text selected it indents the selected lines instead. **Outdent** (button or Shift+Tab) and **Indent** work on all selected lines; empty lines are skipped when indenting.
- Only leading whitespace changes: existing spaces/tabs elsewhere and line endings are never converted. Outdent removes one tab or up to one indent unit of spaces.
- ⋮ ▸ **Indentation…**: width 2, 3, 4 or 8 spaces, or "use tab characters". **YAML files always use spaces.**
- Every indent/outdent is one undo step.

## Undo and redo
- Per tab. Typing is grouped; a pause of over a second, a new line, or any Tab/indent/replace action starts a new step.
- Undo after saving makes the document *Unsaved* again.
- History is kept while the app is open, across switching tabs and screen recreation. After the app is closed or killed it is restored **only for tabs that had unsaved changes** (the newest steps, no redo). See the README for the exact guarantees.

## PDF viewer
Open a PDF like any file (**Open…**, or from a file manager). It opens in its own tab, view-only.
- **Prev / Next** change page; the page number button (`3 / 120`) lets you type a page.
- **− / +** zoom out/in, **Fit** returns to fit-width (100%). Zoom is limited by memory for very large pages. Pinch-zoom is not available.
- Page and zoom are remembered per tab. PDFs that are password-protected or damaged show a message instead.

## Open with… (from a file manager or another app)
Choose **Plain Editor** when opening a `.md .txt .yml .yaml .py .html .htm .json` or `.pdf` file. It opens in a **new tab** and nothing you have open is replaced or closed. If the other app granted only read access the tab is **read-only**: edit as you like, then **Save As…** to keep it. If the file is already open you are switched to it. If 10 tabs are already used you are asked to close one. Links that are not `content://` (for example `file://`) are not supported.

## Protection against losing text
- Closing a tab or leaving with unsaved changes asks first; cancelling any file screen changes nothing.
- Unsaved text of **every** tab is backed up automatically (about 1.5 s after you start editing, and instantly when you leave the app) and restored on the next launch together with your tabs, cursors and scroll positions.
- A file you merely viewed or saved is reopened from its location after a restart; if it can no longer be reached, that tab is closed with a message (nothing unsaved is ever dropped silently).

## Other things to know
- Line endings are kept (LF/CRLF shown in the top bar); mixed endings are converted to the majority style when opened, and you are told. A leading UTF-8 BOM is preserved.
- Files must be UTF-8 text up to 10 MiB. Others are refused with a message and nothing changes.
- ⋮ ▸ **Word wrap** turns soft wrapping off (faster for very long lines), **Monospace font**, **Symbol bar** are remembered.
- Not included: syntax colours, Markdown/HTML preview, regular expressions, PDF search or selection.
