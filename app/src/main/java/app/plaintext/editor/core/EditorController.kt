package app.plaintext.editor.core

enum class SaveStatus { NEW, UNSAVED, SAVING, SAVED }

data class TabInfo(val id: Int, val name: String, val dirty: Boolean, val kind: DocKind, val active: Boolean, val loading: Boolean)

data class UiState(
    val name: String,
    val typeLabel: String,
    val lineEnding: LineEnding,
    val status: SaveStatus,
    val readOnly: Boolean,
    val draftBackedUp: Boolean,
    val canSetName: Boolean,
    val busy: Boolean,
    val kind: DocKind = DocKind.TEXT,
    val tabs: List<TabInfo> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val windowed: Boolean = false,
    /** Position of the visible text within the whole document, 0..1000 (for the large-file seek bar). */
    val navPermille: Int = 0,
    val canNewTab: Boolean = true,
)

/** index = 1-based number of the current match (0 = none); total = matches found (capped -> "N+"). */
class SearchStatus(val index: Int, val total: Int, val capped: Boolean)

fun interface Cancelable { fun cancel() }

interface TaskRunner {
    fun io(task: () -> Unit)
    fun main(task: () -> Unit)
    fun mainDelayed(delayMs: Long, task: () -> Unit): Cancelable
}

interface RecentsStore {
    fun add(uri: String, name: String)
}

/** Everything the controller needs from the UI. All calls happen on the main thread. */
interface EditorView {
    fun render(state: UiState)

    /** Replace the EditText content by a window of the document. Must not be reported back as an edit. */
    fun showWindow(text: String, selStart: Int, selEnd: Int, topOffset: Int, scrollToEnd: Boolean)
    /** Replace [start,end) of the window text; must not be reported back as an edit. */
    fun replaceInWindow(start: Int, end: Int, replacement: String, selStart: Int, selEnd: Int)
    fun selectInWindow(selStart: Int, selEnd: Int)
    fun scrollToEdge(end: Boolean)
    /** Current EditText content (used only to re-synchronise if the editor and the model ever disagree). */
    fun windowText(): String

    fun showPdf(uri: String, name: String, page: Int, zoom: Float)
    fun onSearchStatus(status: SearchStatus)

    fun launchOpenPicker()
    fun launchCreatePicker(suggestedName: String)
    fun confirmUnsaved(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit)
    fun confirmExit(dirtyNames: List<String>, onKeepDrafts: () -> Unit, onDiscardAll: () -> Unit, onCancel: () -> Unit)
    fun showMessage(message: String)
    fun showError(title: String, message: String)
    fun finishApp()
}

private object NullView : EditorView {
    override fun render(state: UiState) {}
    override fun showWindow(text: String, selStart: Int, selEnd: Int, topOffset: Int, scrollToEnd: Boolean) {}
    override fun replaceInWindow(start: Int, end: Int, replacement: String, selStart: Int, selEnd: Int) {}
    override fun selectInWindow(selStart: Int, selEnd: Int) {}
    override fun scrollToEdge(end: Boolean) {}
    override fun windowText() = ""
    override fun showPdf(uri: String, name: String, page: Int, zoom: Float) {}
    override fun onSearchStatus(status: SearchStatus) {}
    override fun launchOpenPicker() {}
    override fun launchCreatePicker(suggestedName: String) {}
    override fun confirmUnsaved(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {}
    override fun confirmExit(dirtyNames: List<String>, onKeepDrafts: () -> Unit, onDiscardAll: () -> Unit, onCancel: () -> Unit) {}
    override fun showMessage(message: String) {}
    override fun showError(title: String, message: String) {}
    override fun finishApp() {}
}

/** One open document. Text tabs own a [TextBuffer] and their own undo history; PDF tabs only remember page and zoom. */
class Tab(val id: Int) {
    var kind = DocKind.TEXT
    var uri: String? = null
    var name = FileNaming.DEFAULT_NAME
    var lineEnding = LineEnding.LF
    var hasBom = false
    var readOnly = false
    var buffer: TextBuffer? = null
    var undo = UndoHistory()
    var dirty = false
    /** Incremented on every change; a save marks the tab clean only if nothing changed since its snapshot. */
    var version = 0L
    var draftVersion = -1L
    var saving = false
    var loading = false
    var selStart = 0
    var selEnd = 0
    /** Document offset of the first visible line (scroll position). */
    var topOffset = 0
    var winStart = 0
    var winEnd = 0
    var pdfPage = 0
    var pdfZoom = 1f
}

/**
 * The workspace state machine: up to [Limits.MAX_TABS] tabs, each with its own text, cursor, scroll, status and undo
 * history; windowing of large documents; find/replace; indentation; save / save-as / export; draft + session
 * persistence; unsaved-changes guards. It contains no Android classes, so it is exercised by JVM unit tests.
 * The Activity may come and go: [attach]/[detach] swap the view while this object (and all tabs) survives.
 */
class EditorController(
    private val repo: DocumentRepository,
    private val session: SessionStore,
    private val recents: RecentsStore,
    private val runner: TaskRunner,
    private val indentSettings: () -> IndentSettings = { IndentSettings() },
    private val draftDelayMs: Long = 1500L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private enum class Purpose { IN_PLACE, SAVE_AS, EXPORT }
    private class Pending(val purpose: Purpose, val tabId: Int, val then: (() -> Unit)?)

    private var view: EditorView = NullView
    private val tabs = ArrayList<Tab>()
    private var activeId = -1
    private var nextId = 1
    private var started = false
    private var disposed = false
    private var pending: Pending? = null
    private var flushTimer: Cancelable? = null
    private var draftWarned = false
    private var searchToken = 0

    // ------------------------------------------------------------------ read-only views of state (UI + tests)

    val isStarted: Boolean get() = started
    val tabCount: Int get() = tabs.size
    val activeIndex: Int get() = tabs.indexOfFirst { it.id == activeId }
    val tabNames: List<String> get() = tabs.map { it.name }
    fun tabId(index: Int): Int? = tabs.getOrNull(index)?.id
    fun isDirty(index: Int): Boolean = tabs.getOrNull(index)?.dirty == true
    /** True if any text tab has unsaved changes. */
    val isDirty: Boolean get() = tabs.any { it.kind == DocKind.TEXT && it.dirty }
    val currentUri: String? get() = active?.uri
    val currentName: String get() = active?.name ?: FileNaming.DEFAULT_NAME
    fun activeText(): String? = active?.buffer?.toString()
    fun textOf(index: Int): String? = tabs.getOrNull(index)?.buffer?.toString()
    fun activeWindow(): Win? = active?.takeIf { it.buffer != null }?.let { Win(it.winStart, it.winEnd) }
    fun activeSelection(): Pair<Int, Int>? = active?.let { it.selStart to it.selEnd }
    fun activeTop(): Int = active?.topOffset ?: 0
    fun activeLength(): Int = active?.buffer?.length ?: 0
    /** The window text the model expects the editor to show (tests compare it with what the view displays). */
    fun expectedWindowText(): String? = active?.buffer?.let { it.substring(active!!.winStart, active!!.winEnd) }

    private val active: Tab? get() = tabs.firstOrNull { it.id == activeId }
    private val activeText: Tab? get() = active?.takeIf { it.kind == DocKind.TEXT && it.buffer != null && !it.loading }
    private fun tab(id: Int) = tabs.firstOrNull { it.id == id }
    private fun totalChars(): Long = tabs.sumOf { (it.buffer?.length ?: 0).toLong() }

    // ------------------------------------------------------------------ lifecycle

    fun attach(v: EditorView) {
        view = v
        if (started) { active?.let { showActive(it) }; render() }
    }

    /** Drops the view. With [v] given, only if it is still the current one (an old Activity must not detach a new one). */
    fun detach(v: EditorView? = null) { if (v == null || view === v) view = NullView }

    /** Position of the first visible line in the whole document, 0..1000 (for the seek bar while scrolling). */
    fun currentPermille(): Int {
        val t = active ?: return 0
        val len = t.buffer?.length ?: return 0
        return if (len == 0) 0 else ((t.topOffset.toLong() * 1000) / len).toInt().coerceIn(0, 1000)
    }

    /** Restores the previous session (tabs, cursors, unsaved drafts) or opens one blank tab. Idempotent. */
    fun start() {
        if (started) return
        started = true
        val meta = try { session.loadMeta() } catch (e: Exception) { null }
        if (meta != null) {
            nextId = meta.nextId
            for (s in meta.tabs.take(Limits.MAX_TABS)) {
                tabs.add(Tab(s.id).also {
                    it.kind = s.kind; it.uri = s.uri; it.name = s.name; it.lineEnding = s.lineEnding; it.hasBom = s.hasBom
                    it.readOnly = s.readOnly; it.selStart = s.selStart; it.selEnd = s.selEnd; it.topOffset = s.topOffset
                    it.pdfPage = s.pdfPage; it.pdfZoom = s.pdfZoom
                })
            }
        }
        // Unsaved text whose tab entry is missing (e.g. a crash right after creating a tab) is never abandoned.
        for (id in try { session.listTextIds() } catch (e: Exception) { emptyList() }) {
            if (tabs.none { it.id == id } && tabs.size < Limits.MAX_TABS) {
                tabs.add(Tab(id).also { it.name = "Recovered draft $id.txt" })
                nextId = maxOf(nextId, id + 1)
            }
        }
        if (tabs.isEmpty()) tabs.add(newBlankTab())
        activeId = if (meta != null && tabs.any { it.id == meta.activeId }) meta.activeId else tabs[0].id
        showActive(active!!)
        render()
        persistMeta()
    }

    /** Call from onPause: guarantees all unsaved text and the tab layout are on disk before the app can be killed. */
    fun onPause() {
        flushTimer?.cancel(); flushTimer = null
        for (t in tabs) if (t.kind == DocKind.TEXT && t.dirty && t.buffer != null && t.draftVersion != t.version) flushTab(t, sync = true)
        persistMeta()
    }

    fun dispose() {
        disposed = true
        flushTimer?.cancel()
    }

    // ------------------------------------------------------------------ tabs

    private fun uniqueUntitled(): String {
        val used = tabs.map { it.name }.toSet()
        if (FileNaming.DEFAULT_NAME !in used) return FileNaming.DEFAULT_NAME
        var n = 2
        while ("Untitled $n.txt" in used) n++
        return "Untitled $n.txt"
    }

    private fun newBlankTab(): Tab = Tab(nextId++).also { it.buffer = TextBuffer(); it.name = uniqueUntitled() }

    fun requestNew() {
        if (tabs.size >= Limits.MAX_TABS) { tooManyTabs(); return }
        val t = newBlankTab(); tabs.add(t); activate(t)
    }

    private fun tooManyTabs() =
        view.showError("Too many tabs", "${Limits.MAX_TABS} tabs are open. Close one to open another document.")

    fun selectTab(id: Int) {
        val t = tab(id) ?: return
        if (t.id != activeId) activate(t)
    }

    fun nextTab(delta: Int) {
        if (tabs.size < 2) return
        val i = (activeIndex + delta + tabs.size) % tabs.size
        activate(tabs[i])
    }

    fun closeTab(id: Int) {
        val t = tab(id) ?: return
        if (t.saving) { view.showMessage("Saving… please wait."); return }
        if (t.kind == DocKind.TEXT && t.dirty && t.buffer != null) {
            if (t.id != activeId) activate(t)
            view.confirmUnsaved(
                t.name,
                onSave = { saveThen { closeNow(t.id) } },
                onDiscard = { closeNow(t.id) },
                onCancel = {},
            )
        } else closeNow(id)
    }

    private fun closeNow(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val wasActive = id == activeId
        if (pending?.tabId == id) pending = null
        tabs.removeAt(idx)
        try { session.clearTab(id) } catch (e: Exception) { /* nothing to clean */ }
        if (tabs.isEmpty()) tabs.add(newBlankTab())
        if (wasActive) activate(tabs[minOf(idx, tabs.size - 1)]) else { render(); persistMeta() }
    }

    private fun activate(t: Tab) {
        activeId = t.id
        showActive(t)
        render()
        persistMeta()
    }

    private fun showActive(t: Tab) {
        when {
            t.kind == DocKind.PDF -> view.showPdf(t.uri ?: "", t.name, t.pdfPage, t.pdfZoom)
            t.buffer == null -> loadTab(t)
            else -> showTabText(t)
        }
    }

    private fun showTabText(t: Tab) {
        val buf = t.buffer!!
        val top = t.topOffset.coerceIn(0, buf.length)
        setWindow(t, Windowing.around(buf, top), t.selStart, t.selEnd, top, false)
    }

    /** Points the tab at [win], clamps the selection (a selection outside the window falls back to the scroll anchor). */
    private fun setWindow(t: Tab, win: Win, selStart: Int, selEnd: Int, top: Int, toEnd: Boolean) {
        val buf = t.buffer!!
        t.winStart = win.start; t.winEnd = win.end
        var a = selStart.coerceIn(0, buf.length)
        var b = selEnd.coerceIn(0, buf.length)
        if (!win.contains(a, b)) { a = top.coerceIn(win.start, win.end); b = a }
        t.selStart = a; t.selEnd = b
        t.topOffset = top.coerceIn(win.start, win.end)
        if (t.id == activeId) {
            view.showWindow(buf.substring(win.start, win.end), a - win.start, b - win.start, t.topOffset - win.start, toEnd)
        }
    }

    // ------------------------------------------------------------------ loading a tab after a restart

    private class Loaded(val buffer: TextBuffer, val history: UndoHistory?, val fromDraft: Boolean, val doc: LoadedDocument?)

    private fun loadTab(t: Tab) {
        if (t.loading) return
        t.loading = true
        render()
        val id = t.id; val uri = t.uri
        runner.io {
            var failure: String? = null
            var loaded: Loaded? = null
            try {
                val draft = if (session.hasText(id)) session.loadText(id) else null
                if (draft != null) {
                    loaded = Loaded(draft, session.loadHistory(id, draft.crc32()), true, null)
                } else if (uri != null) {
                    when (val r = PerfStats.time("open file") { repo.open(uri) }) {
                        is OpenResult.Success -> loaded = Loaded(r.doc.buffer, null, false, r.doc)
                        is OpenResult.Pdf -> failure = "This file is a PDF."
                        is OpenResult.Failure -> failure = r.message
                    }
                } else {
                    loaded = Loaded(TextBuffer(), null, false, null)
                }
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                failure = if (e is OutOfMemoryError) "Not enough memory." else (e.message ?: e.javaClass.simpleName)
            }
            runner.main {
                if (disposed) return@main
                val cur = tab(id) ?: return@main
                cur.loading = false
                val l = loaded
                if (l == null) {
                    view.showError("Couldn't restore “${cur.name}”", (failure ?: "Unknown error") + "\n\nThe tab was closed. The file itself was not changed.")
                    closeNow(id)
                    return@main
                }
                cur.buffer = l.buffer
                l.doc?.let { cur.lineEnding = it.lineEnding; cur.hasBom = it.hasBom; cur.readOnly = cur.readOnly || it.readOnly }
                if (l.fromDraft) {
                    cur.dirty = true; cur.version = 1; cur.draftVersion = 1
                    cur.undo = l.history ?: UndoHistory()
                    view.showMessage("Recovered unsaved changes in “${cur.name}”.")
                }
                if (cur.id == activeId) showTabText(cur)
                render()
            }
        }
    }

    // ------------------------------------------------------------------ editing (called by the UI)

    /** An edit made inside the EditText: [start] is relative to the window. */
    fun onWindowEdit(start: Int, removed: String, inserted: String, windowLenAfter: Int) {
        val t = activeText ?: return
        val buf = t.buffer!!
        val gs = t.winStart + start
        val expectedLen = (t.winEnd - t.winStart) + inserted.length - removed.length
        val consistent = start >= 0 && gs + removed.length <= buf.length && expectedLen == windowLenAfter &&
            buf.substring(gs, gs + removed.length) == removed
        if (!consistent) { resync(t); return }
        buf.replace(gs, gs + removed.length, inserted)
        t.winEnd += inserted.length - removed.length
        t.undo.record(gs, removed, inserted)
        markEdited(t)
    }

    /** The editor and the model disagreed (should not happen): trust the editor text, reset undo, tell the user. */
    private fun resync(t: Tab) {
        val w = view.windowText()
        val buf = t.buffer!!
        val ws = t.winStart.coerceIn(0, buf.length)
        val we = t.winEnd.coerceIn(ws, buf.length)
        buf.replace(ws, we, w)
        t.winEnd = ws + w.length
        t.undo.clear()
        markEdited(t)
        view.showMessage("The editor was re-synchronised; undo history was reset.")
    }

    fun onSelectionChanged(selStart: Int, selEnd: Int) {
        val t = activeText ?: return
        t.selStart = (t.winStart + minOf(selStart, selEnd)).coerceIn(0, t.buffer!!.length)
        t.selEnd = (t.winStart + maxOf(selStart, selEnd)).coerceIn(0, t.buffer!!.length)
    }

    /** [topInWindow]: window offset of the first visible character. Called often; must stay cheap. */
    fun onScrolled(topInWindow: Int) {
        val t = activeText ?: return
        t.topOffset = (t.winStart + topInWindow).coerceIn(t.winStart, t.winEnd)
    }

    fun onPdfState(page: Int, zoom: Float) {
        val t = active ?: return
        if (t.kind != DocKind.PDF) return
        t.pdfPage = maxOf(page, 0); t.pdfZoom = PdfMath.clampZoom(zoom)
    }

    private fun markEdited(t: Tab) {
        t.version++
        t.dirty = true
        render()
        if (flushTimer == null) {
            val delay = if ((t.buffer?.length ?: 0) > 2_000_000) draftDelayMs * 3 else draftDelayMs
            flushTimer = runner.mainDelayed(delay) { flushTimer = null; if (!disposed) flushAll() }
        }
    }

    /** Programmatic edit (undo/redo, replace, indent): updates the model and the EditText in step. */
    private fun applyEdit(t: Tab, start: Int, end: Int, repl: String, record: Boolean, selStart: Int, selEnd: Int) {
        val buf = t.buffer!!
        val removed = buf.substring(start, end)
        if (removed == repl) { select(t, selStart, selEnd); return }
        buf.replace(start, end, repl)
        if (record) t.undo.record(start, removed, repl, mergeable = false)
        val delta = repl.length - removed.length
        val inside = t.winStart <= start && end <= t.winEnd
        if (inside) t.winEnd += delta
        t.selStart = selStart; t.selEnd = selEnd
        markEdited(t)
        if (inside) {
            t.selStart = selStart.coerceIn(t.winStart, t.winEnd); t.selEnd = selEnd.coerceIn(t.winStart, t.winEnd)
            view.replaceInWindow(start - t.winStart, end - t.winStart, repl, t.selStart - t.winStart, t.selEnd - t.winStart)
        } else {
            setWindow(t, Windowing.around(buf, start), selStart, selEnd, buf.lineStart(start), false)
        }
    }

    private fun select(t: Tab, a: Int, b: Int) {
        val buf = t.buffer!!
        t.selStart = a; t.selEnd = b
        if (t.winStart <= a && b <= t.winEnd) view.selectInWindow(a - t.winStart, b - t.winStart)
        else setWindow(t, Windowing.around(buf, a), a, b, buf.lineStart(a), false)
    }

    // ------------------------------------------------------------------ navigation in large documents

    /** Scrolled to a window edge: move the window one step in that direction (the anchor line stays on screen). */
    fun requestShift(dir: Int) {
        val t = activeText ?: return
        val n = Windowing.shifted(t.buffer!!, Win(t.winStart, t.winEnd), dir) ?: return
        PerfStats.time("shift window") { setWindow(t, n, t.selStart, t.selEnd, t.topOffset, false) }
    }

    fun jumpTop() {
        val t = activeText ?: return
        t.selStart = 0; t.selEnd = 0; t.topOffset = 0
        if (t.winStart == 0) { view.selectInWindow(0, 0); view.scrollToEdge(false) }
        else PerfStats.time("jump top") { setWindow(t, Windowing.top(t.buffer!!), 0, 0, 0, false) }
        render()
    }

    fun jumpBottom() {
        val t = activeText ?: return
        val buf = t.buffer!!; val len = buf.length
        t.selStart = len; t.selEnd = len; t.topOffset = buf.lineStart(len)
        if (t.winEnd == len) { view.selectInWindow(len - t.winStart, len - t.winStart); view.scrollToEdge(true) }
        else PerfStats.time("jump bottom") { setWindow(t, Windowing.bottom(buf), len, len, t.topOffset, true) }
        render()
    }

    /** Seek bar: show the part of the document at [permille]/1000 of its length. */
    fun jumpToPermille(permille: Int) {
        val t = activeText ?: return
        val buf = t.buffer!!
        val off = ((buf.length.toLong() * permille.coerceIn(0, 1000)) / 1000).toInt()
        val top = buf.lineStart(off)
        PerfStats.time("seek") { setWindow(t, Windowing.around(buf, off), off, off, top, false) }
        render()
    }

    // ------------------------------------------------------------------ undo / redo

    fun undo() = history(true)
    fun redo() = history(false)

    private fun history(isUndo: Boolean) {
        val t = activeText ?: return
        val e = (if (isUndo) t.undo.popUndo() else t.undo.popRedo()) ?: return
        val ok = when (e) {
            is UndoHistory.Edit -> applyEditHistory(t, e, isUndo)
            is UndoHistory.Bulk -> applyBulkHistory(t, e, isUndo)
        }
        if (!ok) {
            t.undo.clear()
            view.showMessage("Undo history was reset because the text no longer matches it.")
            render()
        }
    }

    private fun applyEditHistory(t: Tab, e: UndoHistory.Edit, isUndo: Boolean): Boolean {
        val buf = t.buffer!!
        val expected = if (isUndo) e.inserted else e.removed
        val repl = if (isUndo) e.removed else e.inserted
        val end = e.start + expected.length
        if (e.start < 0 || end > buf.length || buf.substring(e.start, end) != expected) return false
        applyEdit(t, e.start, end, repl, record = false, selStart = e.start + repl.length, selEnd = e.start + repl.length)
        return true
    }

    private fun applyBulkHistory(t: Tab, b: UndoHistory.Bulk, isUndo: Boolean): Boolean {
        val buf = t.buffer!!
        val n = b.starts.size
        val starts = if (isUndo) b.newStarts() else b.starts
        val lens = IntArray(n) { if (isUndo) b.replacement.length else b.removed[it].length }
        for (i in 0 until n) {                              // verify before touching anything
            val s = starts[i]
            val expected: CharSequence = if (isUndo) b.replacement else b.removed[i]
            if (s < 0 || s + lens[i] > buf.length) return false
            for (k in 0 until lens[i]) if (buf[s + k] != expected[k]) return false
        }
        buf.replaceMany(starts, lens, n) { if (isUndo) b.removed[it] else b.replacement }
        val focus = if (n > 0) (if (isUndo) starts[0] else b.mapForward(b.starts[0])) else 0
        markEdited(t)
        t.selStart = focus; t.selEnd = focus
        setWindow(t, Windowing.around(buf, focus), focus, focus, buf.lineStart(focus), false)
        return true
    }

    // ------------------------------------------------------------------ find & replace

    private fun opts(q: String, cs: Boolean, ww: Boolean) = TextSearch.Options(q, cs, ww)

    /** Selects the next/previous match (wrapping). Returns false if there is none. */
    fun find(query: String, caseSensitive: Boolean, wholeWord: Boolean, forward: Boolean): Boolean {
        val t = activeText ?: return false
        val o = opts(query, caseSensitive, wholeWord)
        if (!o.valid) return false
        val buf = t.buffer!!
        val from = if (forward) t.selEnd else t.selStart
        val m = PerfStats.time("find") { TextSearch.find(buf, o, from, forward, wrap = true) }
        if (m < 0) { view.onSearchStatus(SearchStatus(0, 0, false)); view.showMessage("No matches"); return false }
        if ((forward && m < from) || (!forward && m >= from)) view.showMessage("Wrapped around")
        select(t, m, m + query.length)
        refreshSearchCount(query, caseSensitive, wholeWord)
        return true
    }

    /** Recounts matches in the background (on a snapshot) and reports "n of total" to the find bar. */
    fun refreshSearchCount(query: String, caseSensitive: Boolean, wholeWord: Boolean) {
        val t = activeText ?: return
        val o = opts(query, caseSensitive, wholeWord)
        val token = ++searchToken
        if (!o.valid) { view.onSearchStatus(SearchStatus(0, 0, false)); return }
        val buf = t.buffer!!
        val snap = try { TextBuffer.wrap(buf.copyChars(), buf.length) } catch (e: OutOfMemoryError) { return }
        val ref = t.selStart
        val isCurrent = t.selEnd - t.selStart == query.length && TextSearch.matchesAt(buf, t.selStart, o)
        val id = t.id; val v = t.version
        runner.io {
            val c = PerfStats.time("count matches") { TextSearch.count(snap, o, ref, 1_000_000, cancelled = { token != searchToken }) }
            runner.main {
                val cur = tab(id)
                if (!disposed && token == searchToken && cur != null && cur.version == v && id == activeId) {
                    view.onSearchStatus(SearchStatus(if (isCurrent) c.before + 1 else 0, c.total, c.capped))
                }
            }
        }
    }

    fun replaceCurrent(query: String, replacement: String, caseSensitive: Boolean, wholeWord: Boolean) {
        val t = activeText ?: return
        val o = opts(query, caseSensitive, wholeWord)
        if (!o.valid) return
        val buf = t.buffer!!
        if (t.selEnd - t.selStart == query.length && TextSearch.matchesAt(buf, t.selStart, o)) {
            val s = t.selStart
            applyEdit(t, s, s + query.length, replacement, record = true, selStart = s + replacement.length, selEnd = s + replacement.length)
        }
        find(query, caseSensitive, wholeWord, true)
    }

    /** Replaces every match in one undoable step. Computed on a snapshot off the main thread. */
    fun replaceAll(query: String, replacement: String, caseSensitive: Boolean, wholeWord: Boolean) {
        val t = activeText ?: return
        val o = opts(query, caseSensitive, wholeWord)
        if (!o.valid) return
        val buf = t.buffer!!
        val snap = snapshot(buf, "replace in") ?: return
        val id = t.id; val v = t.version
        runner.io {
            val plan = PerfStats.time("replace all") { TextSearch.planReplaceAll(snap, o, replacement, Limits.MAX_REPLACE_MATCHES) }
            val anyMatch = plan != null || TextSearch.find(snap, o, 0, true, false) >= 0
            runner.main {
                if (disposed) return@main
                val cur = tab(id) ?: return@main
                if (cur.version != v) { view.showMessage("The text changed while replacing; nothing was replaced. Try again."); return@main }
                if (plan == null) {
                    view.showMessage(if (anyMatch) "Too many matches to replace at once (limit ${Limits.MAX_REPLACE_MATCHES})." else "No matches")
                    return@main
                }
                val oldCursor = cur.selStart
                cur.buffer!!.assign(plan.result, plan.resultLen)
                val bulk = UndoHistory.Bulk(plan.starts, plan.removed, replacement, clock())
                val kept = cur.undo.recordBulk(bulk)
                val focus = bulk.mapForward(oldCursor)
                markEdited(cur)
                if (cur.id == activeId) setWindow(cur, Windowing.around(cur.buffer!!, focus), focus, focus, cur.buffer!!.lineStart(focus), false)
                view.showMessage(
                    "Replaced ${plan.starts.size} occurrence${if (plan.starts.size == 1) "" else "s"}" +
                        if (kept) "." else ". Too large to undo: the undo history was cleared."
                )
                view.onSearchStatus(SearchStatus(0, 0, false))
            }
        }
    }

    // ------------------------------------------------------------------ indentation

    private fun effectiveIndent(t: Tab) = IndentTools.effective(indentSettings(), t.name)

    fun tabKey() { val t = activeText ?: return; applyPlan(t, IndentTools.tab(t.buffer!!, t.selStart, t.selEnd, effectiveIndent(t))) }
    fun indentSelection() { val t = activeText ?: return; applyPlan(t, IndentTools.indent(t.buffer!!, t.selStart, t.selEnd, effectiveIndent(t))) }
    fun outdentSelection() {
        val t = activeText ?: return
        val p = IndentTools.outdent(t.buffer!!, t.selStart, t.selEnd, effectiveIndent(t))
        if (p == null) view.showMessage("Nothing to outdent") else applyPlan(t, p)
    }

    private fun applyPlan(t: Tab, p: EditPlan) = applyEdit(t, p.start, p.end, p.replacement, record = true, selStart = p.selStart, selEnd = p.selEnd)

    // ------------------------------------------------------------------ files

    fun setFileName(raw: String) {
        val t = activeText ?: return
        if (t.uri != null) return
        t.name = FileNaming.ensureExtension(raw)
        render(); persistMeta()
    }

    fun requestOpen() = view.launchOpenPicker()

    /** Result of the system open picker; null means the user cancelled (nothing changes). */
    fun onOpenPicked(target: String?) { if (target != null) openInTab(target, null) }

    fun openRecent(target: String) = openInTab(target, null)

    /** A file handed to us by another app (ACTION_VIEW / ACTION_EDIT). [writable] = false if write access was not granted. */
    fun openExternal(target: String, writable: Boolean) = openInTab(target, writable)

    /** An untouched blank tab (never edited, never saved, no history): opening a file reuses it instead of adding a tab. */
    private fun isPristine(t: Tab) =
        t.kind == DocKind.TEXT && t.uri == null && !t.dirty && !t.loading && !t.saving && t.buffer?.length == 0 && !t.undo.canUndo && !t.undo.canRedo

    private fun freeSlots(): Int = Limits.MAX_TABS - tabs.size + (if (active?.let { isPristine(it) } == true) 1 else 0)

    private fun openInTab(target: String, writable: Boolean?) {
        tabs.firstOrNull { it.uri == target }?.let { activate(it); view.showMessage("“${it.name}” is already open."); return }
        if (freeSlots() <= 0) { tooManyTabs(); return }
        runner.io {
            val r = PerfStats.time("open file") { repo.open(target) }
            runner.main { if (!disposed) onOpened(r, target, writable) }
        }
    }

    private fun onOpened(r: OpenResult, target: String, writable: Boolean?) {
        if (freeSlots() <= 0) { tooManyTabs(); return }
        tabs.firstOrNull { it.uri == target }?.let { activate(it); return }
        val blank = active?.takeIf { isPristine(it) }
        fun addTab(t: Tab) {
            if (blank != null) { tabs.remove(blank); try { session.clearTab(blank.id) } catch (e: Exception) { } }
            tabs.add(t)
        }
        when (r) {
            is OpenResult.Failure -> view.showError("Couldn't open file", r.message + "\n\nYour open documents were not changed.")
            is OpenResult.Pdf -> {
                val t = Tab(nextId++).also { it.kind = DocKind.PDF; it.uri = r.uri; it.name = r.name; it.readOnly = true }
                addTab(t); recents.add(r.uri, r.name); activate(t)
            }
            is OpenResult.Success -> {
                val d = r.doc
                if (totalChars() + d.buffer.length > Limits.MAX_TOTAL_CHARS) {
                    view.showError("Not enough room", "Opening “${d.name}” would use more memory than this editor allows for all open tabs. Close a tab and try again.")
                    return
                }
                val t = Tab(nextId++).also {
                    it.uri = d.uri; it.name = d.name; it.buffer = d.buffer; it.lineEnding = d.lineEnding; it.hasBom = d.hasBom
                    it.readOnly = d.readOnly || writable == false
                }
                addTab(t); recents.add(d.uri, d.name); activate(t)
                when {
                    t.readOnly -> view.showMessage("This file is read-only. Use Save As to save a copy.")
                    d.lineEndingsNormalized ->
                        view.showMessage("Mixed line endings were converted to ${d.lineEnding.label}; saving will write ${d.lineEnding.label}.")
                }
            }
        }
    }

    fun requestSave() = saveThen(null)
    fun requestSaveAs() = beginCreate(Purpose.SAVE_AS, null)
    fun requestExport() = beginCreate(Purpose.EXPORT, null)

    /** Result of the system create-document picker; null means cancelled: nothing is written or changed. */
    fun onCreatePicked(target: String?) {
        val p = pending
        pending = null
        if (target == null) return
        val t = p?.let { tab(it.tabId) }
        if (p == null || t == null || t.buffer == null) {
            view.showError(
                "Save interrupted",
                "Android restarted the app while the file picker was open, so nothing was written to the new file " +
                    "(it may exist but be empty). Your text is kept as a draft; choose Save As again."
            )
            return
        }
        writeNow(t, target, p.purpose, p.then)
    }

    /** Back button / leaving the app. */
    fun requestExit() {
        if (tabs.any { it.saving }) { view.showMessage("Saving… please wait."); return }
        val dirty = tabs.filter { it.kind == DocKind.TEXT && it.dirty }
        if (dirty.isEmpty()) { onPause(); view.finishApp(); return }
        view.confirmExit(
            dirty.map { it.name },
            onKeepDrafts = { onPause(); view.finishApp() },
            onDiscardAll = { discardAll(); view.finishApp() },
            onCancel = {},
        )
    }

    private fun discardAll() {
        flushTimer?.cancel(); flushTimer = null
        for (t in tabs) try { session.clearTab(t.id) } catch (e: Exception) { }
        tabs.clear()
        tabs.add(newBlankTab())
        activeId = tabs[0].id
        showActive(tabs[0]); render(); persistMeta()
    }

    private fun saveThen(then: (() -> Unit)?) {
        val t = active ?: return
        if (t.kind == DocKind.PDF) { view.showMessage("PDF files are view-only."); return }
        if (t.buffer == null || t.loading) return
        if (t.saving) { view.showMessage("Saving… please try again in a moment."); return }
        val target = t.uri
        if (target == null || t.readOnly) {
            if (t.readOnly) view.showMessage("This file is read-only. Choose where to save a copy.")
            beginCreate(Purpose.SAVE_AS, then)
        } else {
            writeNow(t, target, Purpose.IN_PLACE, then)
        }
    }

    private fun beginCreate(purpose: Purpose, then: (() -> Unit)?) {
        val t = active ?: return
        if (t.kind == DocKind.PDF) { view.showMessage("PDF files are view-only."); return }
        if (t.buffer == null || t.loading) return
        if (t.saving) { view.showMessage("Saving… please try again in a moment."); return }
        pending = Pending(purpose, t.id, then)
        view.launchCreatePicker(FileNaming.ensureExtension(t.name))
    }

    private fun writeNow(t: Tab, target: String, purpose: Purpose, then: (() -> Unit)?) {
        t.saving = true
        render()
        val snapVersion = t.version
        val le = t.lineEnding; val bom = t.hasBom; val id = t.id
        val buf = t.buffer!!
        val snap = snapshot(buf, "save") ?: run { t.saving = false; render(); return }    // consistent snapshot for the I/O thread
        runner.io {
            val r = PerfStats.time("save+verify") { repo.save(target, snap, le, bom) }
            runner.main { if (!disposed) onWriteDone(id, r, target, purpose, snapVersion, then) }
        }
    }

    private fun onWriteDone(id: Int, r: SaveResult, target: String, purpose: Purpose, snapVersion: Long, then: (() -> Unit)?) {
        val t = tab(id) ?: return
        t.saving = false
        when (r) {
            is SaveResult.Failure -> {
                if (t.dirty) flushTab(t, sync = true)
                render()
                val title = if (purpose == Purpose.EXPORT) "Export failed" else "Not saved"
                view.showError(
                    title,
                    r.message + "\n\nNothing was reported as saved. Your text is still in the editor" +
                        (if (t.dirty) " and in the draft backup." else ".") +
                        if (purpose == Purpose.IN_PLACE) " You can try Save As to a different location." else ""
                )
            }
            is SaveResult.Success -> {
                val shownName = r.displayName?.takeIf { it.isNotBlank() } ?: DocumentRepository.fallbackName(target)
                if (purpose != Purpose.EXPORT) {
                    t.uri = target
                    if (purpose == Purpose.SAVE_AS) t.name = shownName
                    t.readOnly = false
                    recents.add(target, t.name)
                    if (t.version == snapVersion) {
                        t.dirty = false
                        t.draftVersion = -1
                        try { session.clearTab(t.id) } catch (e: Exception) { }
                    } else {
                        flushAll()
                    }
                    persistMeta()
                }
                render()
                view.showMessage(if (purpose == Purpose.EXPORT) "Exported a copy to $shownName" else "Saved ${t.name}")
                if (r.exceedsOpenLimit) {
                    view.showMessage("Note: this file is over ${Limits.MAX_OPEN_BYTES / (1024 * 1024)} MiB, which this app cannot reopen.")
                }
                if (then != null) {
                    if (t.version == snapVersion) then()
                    else view.showMessage("The text changed while saving, so the tab was not closed.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ drafts and session

    private fun flushAll() {
        for (t in tabs) if (t.kind == DocKind.TEXT && t.dirty && t.buffer != null && t.draftVersion != t.version) flushTab(t, sync = false)
    }

    private fun flushTab(t: Tab, sync: Boolean) {
        val buf = t.buffer ?: return
        if (!t.dirty) return
        val chars = try { buf.copyChars() } catch (e: OutOfMemoryError) { recordDraftResult(t, -1, Result.failure(e)); return }
        val hist = t.undo.serialize()
        val v = t.version; val id = t.id
        val epoch = session.epoch(id)
        if (sync) {
            recordDraftResult(t, v, runCatching { session.saveText(id, chars, chars.size, hist, epoch) })
        } else {
            runner.io {
                val res = runCatching { PerfStats.time("draft write") { session.saveText(id, chars, chars.size, hist, epoch) } }
                runner.main { if (!disposed) tab(id)?.let { recordDraftResult(it, v, res) } }
            }
        }
    }

    private fun recordDraftResult(t: Tab, v: Long, res: Result<Boolean>) {
        if (res.isSuccess) {
            if (res.getOrNull() == true && v > t.draftVersion) t.draftVersion = v
        } else if (!draftWarned) {
            draftWarned = true
            view.showMessage("Warning: couldn't write the draft backup. Save to a file soon.")
        }
        render()
    }

    private fun persistMeta() {
        val snaps = tabs.map {
            TabSnapshot(
                it.id, it.kind, it.uri, it.name, it.lineEnding, it.hasBom, it.readOnly,
                it.selStart, it.selEnd, it.topOffset, it.pdfPage, it.pdfZoom,
            )
        }
        try { session.saveMeta(SessionMeta(activeId, nextId, snaps)) } catch (e: Exception) { /* next pause retries */ }
    }

    /** Copy of the text for a background thread; null (and a message) if the device is out of memory. */
    private fun snapshot(buf: TextBuffer, what: String): TextBuffer? = try {
        TextBuffer.wrap(buf.copyChars(), buf.length)
    } catch (e: OutOfMemoryError) {
        view.showError("Not enough memory", "There is not enough memory to $what this large document. Close other tabs and try again. Your text is unchanged.")
        null
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val t = active ?: return
        val status = when {
            t.saving -> SaveStatus.SAVING
            t.dirty -> SaveStatus.UNSAVED
            t.uri == null -> SaveStatus.NEW
            else -> SaveStatus.SAVED
        }
        val len = t.buffer?.length ?: 0
        view.render(
            UiState(
                name = t.name,
                typeLabel = FileNaming.typeLabel(t.name),
                lineEnding = t.lineEnding,
                status = status,
                readOnly = t.readOnly,
                draftBackedUp = t.dirty && t.draftVersion == t.version,
                canSetName = t.kind == DocKind.TEXT && t.uri == null,
                busy = t.loading,
                kind = t.kind,
                tabs = tabs.map { TabInfo(it.id, it.name, it.kind == DocKind.TEXT && it.dirty, it.kind, it.id == activeId, it.loading) },
                canUndo = t.undo.canUndo && t.kind == DocKind.TEXT,
                canRedo = t.undo.canRedo && t.kind == DocKind.TEXT,
                windowed = Windowing.isWindowed(len),
                navPermille = if (len == 0) 0 else ((t.topOffset.toLong() * 1000) / len).toInt().coerceIn(0, 1000),
                canNewTab = tabs.size < Limits.MAX_TABS,
            )
        )
    }
}
