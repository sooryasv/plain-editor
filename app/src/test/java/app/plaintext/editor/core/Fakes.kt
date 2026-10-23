package app.plaintext.editor.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** In-memory stand-in for the Storage Access Framework, with failure injection. */
class FakeStorage : DocumentStorage {
    val files = HashMap<String, ByteArray>()
    val names = HashMap<String, String>()
    val mimes = HashMap<String, String>()
    val readOnly = HashSet<String>()
    var failOpenOutput: Exception? = null
    var failMidWrite = false            // writes half the bytes, then throws (file is left truncated, like a real failure)
    var silentlyTruncateTo: Int? = null // provider "accepts" the write but stores fewer bytes
    private var counter = 0

    /** Simulates the system CREATE_DOCUMENT picker: makes a new, empty file and returns its URI. */
    fun create(name: String): String {
        val u = "content://fake/${counter++}/$name"
        files[u] = ByteArray(0); names[u] = name
        return u
    }

    fun put(name: String, bytes: ByteArray, mime: String? = null): String =
        create(name).also { files[it] = bytes; if (mime != null) mimes[it] = mime }

    override fun openInput(uri: String): InputStream =
        ByteArrayInputStream(files[uri] ?: throw FileNotFoundException(uri))

    override fun openOutput(uri: String): OutputStream {
        if (!files.containsKey(uri)) throw FileNotFoundException(uri)
        failOpenOutput?.let { throw it }
        if (uri in readOnly) throw SecurityException("read-only")
        files[uri] = ByteArray(0) // "wt" truncates immediately, as on a real device
        return object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failMidWrite) {
                    super.write(b, off, len / 2)
                    files[uri] = toByteArray()
                    throw IOException("disk full")
                }
                super.write(b, off, len)
            }

            override fun close() {
                super.close()
                val all = toByteArray()
                files[uri] = silentlyTruncateTo?.let { all.copyOf(minOf(it, all.size)) } ?: all
            }
        }
    }

    override fun info(uri: String): DocumentInfo {
        val b = files[uri] ?: throw FileNotFoundException(uri)
        return DocumentInfo(names[uri], b.size.toLong(), uri !in readOnly, mimes[uri])
    }
}

/** Simulates an EditText holding one window of the document, plus the other things the real UI does. */
class FakeView : EditorView {
    var text = ""
    var selS = 0
    var selE = 0
    var top = 0
    var state: UiState? = null
    var lastSearch: SearchStatus? = null
    val messages = ArrayList<String>()
    val errors = ArrayList<Pair<String, String>>()
    var openLaunches = 0
    val createLaunches = ArrayList<String>()
    val pdfShown = ArrayList<List<Any>>()
    var edgeScrolls = ArrayList<Boolean>()
    var finished = false
    var confirmCalls = 0
    var exitCalls = 0
    var windowShows = 0
    /** "save", "discard" or "cancel" - what the simulated user taps in the unsaved-changes dialog. */
    var confirmChoice = "cancel"
    /** "keep", "discard" or "cancel" - exit dialog. */
    var exitChoice = "cancel"
    lateinit var controller: EditorController

    override fun render(state: UiState) { this.state = state }
    override fun showWindow(text: String, selStart: Int, selEnd: Int, topOffset: Int, scrollToEnd: Boolean) {
        windowShows++; this.text = text; selS = selStart; selE = selEnd; top = topOffset
    }
    override fun replaceInWindow(start: Int, end: Int, replacement: String, selStart: Int, selEnd: Int) {
        text = text.substring(0, start) + replacement + text.substring(end); selS = selStart; selE = selEnd
    }
    override fun selectInWindow(selStart: Int, selEnd: Int) { selS = selStart; selE = selEnd }
    override fun scrollToEdge(end: Boolean) { edgeScrolls.add(end) }
    override fun windowText() = text
    override fun showPdf(uri: String, name: String, page: Int, zoom: Float) { pdfShown.add(listOf(uri, name, page, zoom)) }
    override fun onSearchStatus(status: SearchStatus) { lastSearch = status }
    override fun launchOpenPicker() { openLaunches++ }
    override fun launchCreatePicker(suggestedName: String) { createLaunches.add(suggestedName) }
    override fun confirmUnsaved(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
        confirmCalls++
        when (confirmChoice) { "save" -> onSave(); "discard" -> onDiscard(); else -> onCancel() }
    }
    override fun confirmExit(dirtyNames: List<String>, onKeepDrafts: () -> Unit, onDiscardAll: () -> Unit, onCancel: () -> Unit) {
        exitCalls++
        when (exitChoice) { "keep" -> onKeepDrafts(); "discard" -> onDiscardAll(); else -> onCancel() }
    }
    override fun showMessage(message: String) { messages.add(message) }
    override fun showError(title: String, message: String) { errors.add(title to message) }
    override fun finishApp() { finished = true }

    // --- simulated user input -------------------------------------------------------------------------

    /** Types [s] at the end of the shown window (what the old single-document tests called "typing"). */
    fun type(s: String) { typeAt(text.length, s) }

    fun typeAt(pos: Int, s: String) {
        text = text.substring(0, pos) + s + text.substring(pos); selS = pos + s.length; selE = selS
        controller.onWindowEdit(pos, "", s, text.length)
        controller.onSelectionChanged(selS, selE)
    }

    fun deleteRange(a: Int, b: Int) {
        val removed = text.substring(a, b); text = text.substring(0, a) + text.substring(b); selS = a; selE = a
        controller.onWindowEdit(a, removed, "", text.length)
    }

    /** Select-all + paste: replaces the whole window text. */
    fun replaceAll(s: String) {
        val old = text; text = s; selS = s.length; selE = s.length
        controller.onWindowEdit(0, old, s, s.length)
    }

    fun select(a: Int, b: Int) { selS = a; selE = b; controller.onSelectionChanged(a, b) }
}

class TestRunner(var deferIo: Boolean = false) : TaskRunner {
    private val ioQueue = ArrayList<() -> Unit>()
    private val mainQueue = ArrayList<() -> Unit>()
    private class Timer(val task: () -> Unit) { var cancelled = false }
    private val timers = ArrayList<Timer>()

    override fun io(task: () -> Unit) { if (deferIo) ioQueue.add(task) else task() }
    override fun main(task: () -> Unit) { if (deferIo) mainQueue.add(task) else task() }
    override fun mainDelayed(delayMs: Long, task: () -> Unit): Cancelable {
        val t = Timer(task); timers.add(t); return Cancelable { t.cancelled = true }
    }

    fun drainIo() { while (ioQueue.isNotEmpty()) ioQueue.removeAt(0)() }
    fun drainMain() { while (mainQueue.isNotEmpty()) mainQueue.removeAt(0)() }
    fun drain() { while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) { drainIo(); drainMain() } }
    fun fireTimers() {
        val due = timers.filter { !it.cancelled }; timers.clear(); due.forEach { it.task() }
    }
}

class TestRecents : RecentsStore {
    val added = ArrayList<Pair<String, String>>()
    override fun add(uri: String, name: String) { added.add(uri to name) }
}

/** One "app run": controller + fake view over a (possibly shared) private directory and storage. */
class Env(
    val storage: FakeStorage = FakeStorage(),
    val dir: File,
    val runner: TestRunner = TestRunner(),
    var indent: IndentSettings = IndentSettings(),
) {
    val view = FakeView()
    val session = SessionStore(dir)
    val recents = TestRecents()
    val controller = EditorController(DocumentRepository(storage), session, recents, runner, { indent })
    init { view.controller = controller; controller.attach(view) }

    fun bytes(uri: String) = storage.files[uri]!!

    /** The window text shown by the (fake) editor must always equal what the model says it should be. */
    fun assertInSync() {
        val exp = controller.expectedWindowText() ?: return
        org.junit.Assert.assertEquals("editor window and model diverged", exp, view.text)
    }

    fun draftText(index: Int = controller.activeIndex): String? = controller.tabId(index)?.let { session.loadText(it)?.toString() }

    /** A deterministic document of about [chars] characters with numbered lines. */
    companion object {
        fun doc(chars: Int, lineWidth: Int = 72): String {
            val sb = StringBuilder(chars + 100)
            var i = 0
            while (sb.length < chars) { sb.append(("line $i ünï ").padEnd(lineWidth, 'x')).append('\n'); i++ }
            return sb.toString()
        }
    }
}
