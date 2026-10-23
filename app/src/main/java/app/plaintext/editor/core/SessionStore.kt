package app.plaintext.editor.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.util.Properties

enum class DocKind { TEXT, PDF }

/** What is remembered about one tab between app runs (the text itself is stored separately, only when unsaved). */
class TabSnapshot(
    val id: Int,
    val kind: DocKind,
    val uri: String?,
    val name: String,
    val lineEnding: LineEnding,
    val hasBom: Boolean,
    val readOnly: Boolean,
    val selStart: Int,
    val selEnd: Int,
    val topOffset: Int,
    val pdfPage: Int,
    val pdfZoom: Float,
)

class SessionMeta(val activeId: Int, val nextId: Int, val tabs: List<TabSnapshot>)

/**
 * Crash-tolerant session storage in app-private files:
 *  - session.meta        tab list, per-tab file/cursor/scroll info (atomic write)
 *  - tab-N.text          UNSAVED text of tab N as raw UTF-16 code units (exists only while the tab has unsaved edits)
 *  - tab-N.hist          bounded undo history for that text, prefixed with the CRC of the text it belongs to
 * Rule on restore: "a text file exists => the tab was unsaved and is restored from it" (newest data wins).
 * [clearTab] bumps that tab's epoch so a slow background write that started earlier cannot resurrect it.
 */
class SessionStore(private val dir: File) {
    private val lock = Any()
    private val epochs = HashMap<Int, Long>()

    private fun textFile(id: Int) = File(dir, "tab-$id.text")
    private fun histFile(id: Int) = File(dir, "tab-$id.hist")
    private val metaFile get() = File(dir, "session.meta")

    fun epoch(id: Int): Long = synchronized(lock) { epochs[id] ?: 0L }

    /** @return true when written, false when skipped because the tab was cleared after [expectedEpoch] was read. */
    @Throws(IOException::class)
    fun saveText(id: Int, chars: CharArray, len: Int, history: ByteArray?, expectedEpoch: Long): Boolean {
        val crc = Crc.ofChars(chars, len)           // computed outside the lock: can take a while for big text
        synchronized(lock) {
            if ((epochs[id] ?: 0L) != expectedEpoch) return false
            ensureDir()
            val bytes = ByteBuffer.allocate(len * 2).also { it.asCharBuffer().put(chars, 0, len) }.array()
            atomicWrite(textFile(id), bytes)
            if (history != null) {
                val h = ByteBuffer.allocate(8 + history.size).putLong(crc).put(history).array()
                atomicWrite(histFile(id), h)
            } else {
                histFile(id).delete()
            }
            return true
        }
    }

    fun hasText(id: Int): Boolean = synchronized(lock) { textFile(id).isFile }

    /** Unsaved text of a tab, or null if there is none / it is unreadable. */
    fun loadText(id: Int): TextBuffer? = synchronized(lock) {
        val f = textFile(id)
        if (!f.isFile) return null
        val bytes = try { FileInputStream(f).use { it.readBytes() } } catch (e: IOException) { return null }
        if (bytes.size % 2 != 0) return null
        val n = bytes.size / 2
        val chars = TextBuffer.withSlack(n)
        ByteBuffer.wrap(bytes).asCharBuffer().get(chars, 0, n)
        TextBuffer.wrap(chars, n)
    }

    /** History saved for exactly this text (CRC match), else null. */
    fun loadHistory(id: Int, textCrc: Long): UndoHistory? = synchronized(lock) {
        val f = histFile(id)
        if (!f.isFile) return null
        val bytes = try { FileInputStream(f).use { it.readBytes() } } catch (e: IOException) { return null }
        if (bytes.size < 8) return null
        val bb = ByteBuffer.wrap(bytes)
        if (bb.long != textCrc) return null
        UndoHistory.deserialize(bytes.copyOfRange(8, bytes.size))
    }

    fun listTextIds(): List<Int> = synchronized(lock) {
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            Regex("tab-(\\d+)\\.text").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
        }.sorted()
    }

    fun clearTab(id: Int) = synchronized(lock) {
        epochs[id] = (epochs[id] ?: 0L) + 1
        textFile(id).delete(); histFile(id).delete()
        File(dir, "tab-$id.text.tmp").delete(); File(dir, "tab-$id.hist.tmp").delete()
        Unit
    }

    fun clearAll() = synchronized(lock) {
        for (f in dir.listFiles() ?: emptyArray()) {
            Regex("tab-(\\d+)\\.").find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { epochs[it] = (epochs[it] ?: 0L) + 1 }
            f.delete()
        }
        Unit
    }

    @Throws(IOException::class)
    fun saveMeta(m: SessionMeta) = synchronized(lock) {
        ensureDir()
        val p = Properties()
        p.setProperty("active", m.activeId.toString())
        p.setProperty("next", m.nextId.toString())
        p.setProperty("order", m.tabs.joinToString(",") { it.id.toString() })
        for (t in m.tabs) {
            val k = "tab.${t.id}."
            p.setProperty(k + "kind", t.kind.name)
            t.uri?.let { p.setProperty(k + "uri", it) }
            p.setProperty(k + "name", t.name)
            p.setProperty(k + "le", t.lineEnding.name)
            p.setProperty(k + "bom", t.hasBom.toString())
            p.setProperty(k + "ro", t.readOnly.toString())
            p.setProperty(k + "s0", t.selStart.toString())
            p.setProperty(k + "s1", t.selEnd.toString())
            p.setProperty(k + "top", t.topOffset.toString())
            p.setProperty(k + "page", t.pdfPage.toString())
            p.setProperty(k + "zoom", t.pdfZoom.toString())
        }
        val out = java.io.ByteArrayOutputStream()
        OutputStreamWriter(out, Charsets.UTF_8).use { p.store(it, null) }
        atomicWrite(metaFile, out.toByteArray())
    }

    /** Null if there is no (readable) session. Individual damaged entries are skipped. */
    fun loadMeta(): SessionMeta? = synchronized(lock) {
        if (!metaFile.isFile) return null
        val p = Properties()
        try {
            FileInputStream(metaFile).use { InputStreamReader(it, Charsets.UTF_8).use { r -> p.load(r) } }
        } catch (e: Exception) {
            return null
        }
        val ids = (p.getProperty("order") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        val tabs = ids.mapNotNull { id ->
            val k = "tab.$id."
            try {
                TabSnapshot(
                    id = id,
                    kind = DocKind.valueOf(p.getProperty(k + "kind") ?: return@mapNotNull null),
                    uri = p.getProperty(k + "uri"),
                    name = p.getProperty(k + "name") ?: FileNaming.DEFAULT_NAME,
                    lineEnding = runCatching { LineEnding.valueOf(p.getProperty(k + "le")) }.getOrDefault(LineEnding.LF),
                    hasBom = p.getProperty(k + "bom") == "true",
                    readOnly = p.getProperty(k + "ro") == "true",
                    selStart = p.getProperty(k + "s0")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    selEnd = p.getProperty(k + "s1")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    topOffset = p.getProperty(k + "top")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    pdfPage = p.getProperty(k + "page")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    pdfZoom = p.getProperty(k + "zoom")?.toFloatOrNull()?.let { PdfMath.clampZoom(it) } ?: 1f,
                )
            } catch (e: Exception) {
                null
            }
        }
        SessionMeta(
            activeId = p.getProperty("active")?.toIntOrNull() ?: tabs.firstOrNull()?.id ?: 0,
            nextId = maxOf(p.getProperty("next")?.toIntOrNull() ?: 1, (tabs.maxOfOrNull { it.id } ?: 0) + 1),
            tabs = tabs,
        )
    }

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create session directory")
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Cannot replace ${target.name}")
        }
    }
}
