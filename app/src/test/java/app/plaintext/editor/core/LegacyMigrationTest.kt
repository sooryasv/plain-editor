package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.Properties

class LegacyMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Writes a draft exactly the way v1.0's DraftStore did. */
    private fun writeV1Draft(dir: File, text: String, uri: String?, name: String) {
        dir.mkdirs()
        File(dir, "draft.text").writeBytes(ByteBuffer.allocate(text.length * 2).also { it.asCharBuffer().put(text) }.array())
        val p = Properties(); p.setProperty("name", name); uri?.let { p.setProperty("uri", it) }
        p.setProperty("lineEnding", "CRLF"); p.setProperty("bom", "true"); p.setProperty("selStart", "2"); p.setProperty("selEnd", "4")
        File(dir, "draft.meta").outputStream().use { p.store(java.io.OutputStreamWriter(it, Charsets.UTF_8), null) }
    }

    @Test fun anUnsavedV1DraftBecomesTheFirstTabAndIsRecoveredByTheApp() {
        val legacy = File(tmp.root, "draft"); val sess = File(tmp.root, "session")
        val text = "unsaved v1 work 😀\nline 2"
        writeV1Draft(legacy, text, "content://x/doc", "notes.md")
        assertTrue(LegacyDraftMigration.migrate(legacy, SessionStore(sess)))
        assertFalse(File(legacy, "draft.text").exists()); assertFalse(File(legacy, "draft.meta").exists())
        val e = Env(FakeStorage(), sess); e.controller.start()
        assertEquals(text, e.view.text); assertEquals("notes.md", e.controller.currentName); assertEquals("content://x/doc", e.controller.currentUri)
        assertTrue(e.controller.isDirty(0)); assertEquals(LineEnding.CRLF, e.view.state!!.lineEnding); assertEquals(2 to 4, e.view.selS to e.view.selE)
    }

    @Test fun nothingHappensWithoutALegacyDraftOrWhenASessionAlreadyExists() {
        val legacy = File(tmp.root, "draft"); val sess = File(tmp.root, "session")
        assertFalse(LegacyDraftMigration.migrate(legacy, SessionStore(sess)))
        writeV1Draft(legacy, "old", null, "a.txt")
        val store = SessionStore(sess); store.saveMeta(SessionMeta(5, 6, emptyList()))
        assertFalse(LegacyDraftMigration.migrate(legacy, store)); assertTrue(File(legacy, "draft.text").exists())
    }

    @Test fun aDamagedLegacyMetaStillRecoversTheText() {
        val legacy = File(tmp.root, "draft"); val sess = File(tmp.root, "session")
        writeV1Draft(legacy, "precious", null, "x.txt"); File(legacy, "draft.meta").writeText("\u0000garbage \\u12")
        assertTrue(LegacyDraftMigration.migrate(legacy, SessionStore(sess)))
        val e = Env(FakeStorage(), sess); e.controller.start(); assertEquals("precious", e.view.text)
    }
}
