package app.plaintext.editor.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.Properties

/**
 * Version 1.0 kept ONE unsaved draft in files/draft (draft.text = UTF-16BE code units, draft.meta = properties).
 * Version 1.1 keeps a session of tabs. On the first start after an upgrade the old draft becomes tab 1, so
 * nothing the user had not saved is lost. The old files are deleted only after the new copy is written.
 */
object LegacyDraftMigration {
    /** @return true if a legacy draft was migrated. Does nothing if a v1.1 session already exists. */
    fun migrate(legacyDir: File, store: SessionStore): Boolean {
        val textFile = File(legacyDir, "draft.text")
        if (!textFile.isFile || store.loadMeta() != null || store.listTextIds().isNotEmpty()) return false
        val bytes = FileInputStream(textFile).use { it.readBytes() }
        if (bytes.size % 2 != 0) return false
        val chars = CharArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).asCharBuffer().get(chars)
        val p = Properties()
        val metaFile = File(legacyDir, "draft.meta")
        if (metaFile.isFile) try {
            FileInputStream(metaFile).use { InputStreamReader(it, Charsets.UTF_8).use { r -> p.load(r) } }
        } catch (e: Exception) { p.clear() }
        val len = chars.size
        val snap = TabSnapshot(
            id = 1, kind = DocKind.TEXT, uri = p.getProperty("uri"),
            name = p.getProperty("name") ?: FileNaming.DEFAULT_NAME,
            lineEnding = runCatching { LineEnding.valueOf(p.getProperty("lineEnding")) }.getOrDefault(LineEnding.LF),
            hasBom = p.getProperty("bom") == "true", readOnly = false,
            selStart = (p.getProperty("selStart")?.toIntOrNull() ?: len).coerceIn(0, len),
            selEnd = (p.getProperty("selEnd")?.toIntOrNull() ?: len).coerceIn(0, len),
            topOffset = 0, pdfPage = 0, pdfZoom = 1f,
        )
        if (!store.saveText(1, chars, len, null, store.epoch(1))) return false
        store.saveMeta(SessionMeta(1, 2, listOf(snap)))
        textFile.delete(); metaFile.delete()
        File(legacyDir, "draft.text.tmp").delete(); File(legacyDir, "draft.meta.tmp").delete()
        return true
    }
}
