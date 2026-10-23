package app.plaintext.editor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.plaintext.editor.core.Cancelable
import app.plaintext.editor.core.DocumentInfo
import app.plaintext.editor.core.DocumentStorage
import app.plaintext.editor.core.DocumentRepository
import app.plaintext.editor.core.EditorController
import app.plaintext.editor.core.FileNaming
import app.plaintext.editor.core.IndentSettings
import app.plaintext.editor.core.LegacyDraftMigration
import app.plaintext.editor.core.SessionStore
import app.plaintext.editor.core.RecentEntry
import app.plaintext.editor.core.RecentFiles
import app.plaintext.editor.core.RecentsStore
import app.plaintext.editor.core.TaskRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/** DocumentStorage backed by ContentResolver (Storage Access Framework URIs). */
class ContentResolverStorage(private val context: Context) : DocumentStorage {
    private val resolver get() = context.contentResolver

    override fun openInput(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("Provider returned no stream for reading")

    /** "wt" = write + truncate, so saving shorter text never leaves old bytes at the end of the file. */
    override fun openOutput(uri: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw FileNotFoundException("Provider returned no stream for writing")

    override fun info(uri: String): DocumentInfo {
        val u = Uri.parse(uri)
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // Provider does not answer metadata queries; name/size stay unknown.
        }
        var canWrite = true
        try {
            if (DocumentsContract.isDocumentUri(context, u)) {
                resolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) {
                        canWrite = (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                    }
                }
            }
        } catch (e: Exception) {
            // Unknown: assume writable; a failed save is reported honestly if it is not.
        }
        val mime = try { resolver.getType(u) } catch (e: Exception) { null }
        return DocumentInfo(name, size, canWrite, mime)
    }
}

class AndroidTaskRunner : TaskRunner {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "editor-io").also { it.isDaemon = true } }

    override fun io(task: () -> Unit) { executor.execute { task() } }
    override fun main(task: () -> Unit) { handler.post { task() } }
    override fun mainDelayed(delayMs: Long, task: () -> Unit): Cancelable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return Cancelable { handler.removeCallbacks(r) }
    }
}

/** The three view toggles. Nothing else is configurable on purpose. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var wrap: Boolean
        get() = sp.getBoolean("wrap", true)
        set(v) { sp.edit().putBoolean("wrap", v).apply() }
    var mono: Boolean
        get() = sp.getBoolean("mono", true)
        set(v) { sp.edit().putBoolean("mono", v).apply() }
    var symbolBar: Boolean
        get() = sp.getBoolean("symbols", true)
        set(v) { sp.edit().putBoolean("symbols", v).apply() }
    /** Indent unit: 2, 3, 4 or 8 spaces, or one tab character. YAML files ignore the tab setting. */
    var indentWidth: Int
        get() = sp.getInt("indentWidth", 4)
        set(v) { sp.edit().putInt("indentWidth", v).apply() }
    var useTabs: Boolean
        get() = sp.getBoolean("useTabs", false)
        set(v) { sp.edit().putBoolean("useTabs", v).apply() }
    fun indentSettings() = IndentSettings(indentWidth, useTabs)
}

/** Recent files persisted in SharedPreferences. Persisted URI grants of evicted entries are released. */
class PrefsRecents(private val context: Context) : RecentsStore {
    private val sp = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    private fun load() = RecentFiles.parse(sp.getString("list", null))

    fun list(): List<RecentEntry> = load().list()

    override fun add(uri: String, name: String) {
        val r = load()
        val evicted = r.add(RecentEntry(uri, name, System.currentTimeMillis()))
        sp.edit().putString("list", r.serialize()).apply()
        evicted.forEach { release(it.uri) }
    }

    fun clear() {
        val r = load()
        val old = r.clear()
        sp.edit().putString("list", r.serialize()).apply()
        old.forEach { release(it.uri) }
    }

    private fun release(uri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Permission was not held; nothing to release.
        }
    }
}

object MimeUtil {
    /**
     * MIME type for the CREATE_DOCUMENT picker, derived from the file name's own extension. Several providers
     * append an extension when the MIME type does not match the one in the name (e.g. "script.py" + text/plain
     * becomes "script.py.txt"). Deriving the type from the extension avoids that; unknown extensions use
     * application/octet-stream, which providers leave alone.
     */
    fun forFileName(name: String): String {
        val ext = FileNaming.extensionOf(name) ?: return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

/**
 * Process-wide holder of the workspace. The Activity can be destroyed and recreated (system theme change, memory
 * pressure) while all open tabs, their text, cursors and undo histories live on here; the new Activity just re-attaches.
 * After a real process death the controller restores the session from [SessionStore].
 */
object AppState {
    private var controller: EditorController? = null
    private var recents: PrefsRecents? = null

    @Synchronized
    fun controller(context: Context): EditorController {
        controller?.let { return it }
        val app = context.applicationContext
        val r = PrefsRecents(app)
        recents = r
        val prefs = Prefs(app)
        val session = SessionStore(File(app.filesDir, "session"))
        try { LegacyDraftMigration.migrate(File(app.filesDir, "draft"), session) } catch (e: Exception) { /* keep going: the old draft stays on disk */ }
        val c = EditorController(
            repo = DocumentRepository(ContentResolverStorage(app), tempDir = app.cacheDir),
            session = session,
            recents = r,
            runner = AndroidTaskRunner(),
            indentSettings = { prefs.indentSettings() },
        )
        controller = c
        return c
    }

    @Synchronized fun recents(context: Context): PrefsRecents {
        controller(context)
        return recents!!
    }
}
