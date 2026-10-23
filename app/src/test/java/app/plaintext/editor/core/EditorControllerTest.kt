package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Single-document scenarios from v1.0 (migrated to the tabbed controller): save / save as / export, failure handling,
 * drafts, read-only, line endings. They use an in-memory storage fake and simulate the system pickers by calling
 * onCreatePicked / onOpenPicked; they do NOT exercise real Android UI.
 */
class EditorControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var n = 0
    private fun env(runner: TestRunner = TestRunner(), storage: FakeStorage = FakeStorage()) =
        Env(storage, File(tmp.root, "s${n++}"), runner).also { it.controller.start() }

    private val samples = mapOf(
        "note.txt" to "Plain note\n\nSecond paragraph with trailing spaces   \nUnicode: é ñ ü 日本語 😀\n",
        "doc.md" to "# Title\n\n- item `code`\n  - nested ~~x~~ *y* _z_\n\n```py\nprint('hi')\n```\n\n> quote\\\n",
        "conf.yml" to "root:\n  child:\n    list:\n      - a: 1\n        b: \"two\"  \n\n      - c: [1, 2, {d: e}]\n\ttabbed: yes\n",
        "conf2.yaml" to "key: value\n\nother:\n  - x\n  - y\n",
        "script.py" to "def f(x):\n    if x:\n        return \"é😀\"   \n\n\n    return None\n\nclass A:\n\tdef g(self): pass\n",
        "page.html" to "<!DOCTYPE html>\n<html>\n  <body>\n    <p class=\"a\">héllo &amp; 日本</p>\n\t<br/>   \n  </body>\n</html>\n",
        "page2.htm" to "<p>\n  x\n</p>\n",
        "data.json" to "{\n  \"a\": [1, 2, {\"b\": null}],\n  \"é\": \"😀\",\n  \"t\": \"tab\\there\"\n}\n",
    )

    @Test fun launchGivesBlankNewDocument() {
        val e = env()
        assertEquals("", e.view.text); assertEquals(1, e.controller.tabCount)
        assertEquals(SaveStatus.NEW, e.view.state!!.status); assertEquals("Untitled.txt", e.view.state!!.name)
        assertFalse(e.controller.isDirty)
    }

    @Test fun typingMarksUnsavedAndSaveAsCreatesFile() {
        val e = env()
        e.view.type("hello")
        assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
        e.controller.requestSave()
        assertEquals(listOf("Untitled.txt"), e.view.createLaunches)
        assertEquals("nothing is saved until the picker returns", SaveStatus.UNSAVED, e.view.state!!.status)
        val uri = e.storage.create("Untitled.txt")
        e.controller.onCreatePicked(uri)
        assertEquals("hello", String(e.bytes(uri)))
        assertEquals(SaveStatus.SAVED, e.view.state!!.status)
        assertTrue(e.view.messages.any { it.startsWith("Saved") })
        assertEquals(uri, e.controller.currentUri)
    }

    @Test fun everySupportedTypeExportsReopensAndMatchesExactly() {
        for ((name, content) in samples) {
            val e = env()
            e.view.replaceAll(content)
            e.controller.requestSaveAs()
            assertEquals("Untitled.txt", e.view.createLaunches.last())
            val uri = e.storage.create(name)
            e.controller.onCreatePicked(uri)
            assertArrayEquals("bytes for $name", content.toByteArray(), e.bytes(uri))
            assertEquals(name, e.view.state!!.name)
            val e2 = env(storage = e.storage)
            e2.controller.onOpenPicked(uri)
            assertEquals("reopened content for $name", content, e2.view.text)
            assertEquals("a pristine blank tab is reused", 1, e2.controller.tabCount)
            assertEquals(SaveStatus.SAVED, e2.view.state!!.status)
            e2.assertInSync()
        }
    }

    @Test fun suggestedNameKeepsChosenExtensionWithoutDuplicates() {
        val e = env()
        e.controller.setFileName("report.md.md"); e.view.type("x"); e.controller.requestExport()
        assertEquals("report.md", e.view.createLaunches.last())
        e.controller.setFileName("plain"); e.controller.requestExport()
        assertEquals("plain.txt", e.view.createLaunches.last())
        e.controller.setFileName("data.json"); e.controller.requestExport()
        assertEquals("data.json", e.view.createLaunches.last())
    }

    @Test fun openEditSaveReopen() {
        val s = FakeStorage(); val uri = s.put("todo.md", "# old\n".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(uri)
        assertEquals("# old\n", e.view.text)
        e.view.type("added line\n")
        e.controller.requestSave()
        assertTrue("in-place save needs no picker", e.view.createLaunches.isEmpty())
        assertEquals("# old\nadded line\n", String(s.files[uri]!!))
        assertEquals(SaveStatus.SAVED, e.view.state!!.status)
        val e2 = env(storage = s); e2.controller.onOpenPicked(uri)
        assertEquals("# old\nadded line\n", e2.view.text)
    }

    @Test fun saveAsMakesACopyAndLeavesSourceUntouched() {
        val s = FakeStorage(); val src = s.put("src.md", "original\n".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(src); e.view.type("edit\n")
        e.controller.requestSaveAs()
        val copy = s.create("copy.md"); e.controller.onCreatePicked(copy)
        assertEquals("original\n", String(s.files[src]!!)); assertEquals("original\nedit\n", String(s.files[copy]!!))
        assertEquals(copy, e.controller.currentUri)
        e.view.type("more"); e.controller.requestSave()
        assertEquals("original\n", String(s.files[src]!!)); assertEquals("original\nedit\nmore", String(s.files[copy]!!))
    }

    @Test fun exportCopyKeepsDocumentBindingAndUnsavedState() {
        val s = FakeStorage(); val src = s.put("src.md", "a".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(src); e.view.type("b"); e.controller.requestExport()
        val out = s.create("export.py"); e.controller.onCreatePicked(out)
        assertEquals("ab", String(s.files[out]!!)); assertEquals("a", String(s.files[src]!!))
        assertEquals(src, e.controller.currentUri); assertTrue(e.controller.isDirty)
        assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
    }

    @Test fun cancellingPickersLosesNothing() {
        val e = env()
        e.view.type("keep me")
        e.controller.requestSaveAs(); e.controller.onCreatePicked(null)
        assertEquals("keep me", e.view.text); assertTrue(e.controller.isDirty)
        assertTrue(e.view.errors.isEmpty()); assertFalse(e.view.messages.any { it.startsWith("Saved") })
        e.controller.requestOpen(); e.controller.onOpenPicked(null)
        assertEquals("keep me", e.view.text); assertEquals(0, e.view.confirmCalls); assertEquals(1, e.controller.tabCount)
        assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
    }

    @Test fun failedSaveNeverReportsSuccess() {
        for (inject in listOf<(FakeStorage) -> Unit>(
            { it.failOpenOutput = IOException("provider error") }, { it.failMidWrite = true }, { it.silentlyTruncateTo = 2 },
        )) {
            val s = FakeStorage(); val uri = s.put("a.txt", "orig".toByteArray())
            val e = env(storage = s)
            e.controller.onOpenPicked(uri); e.view.type(" changed")
            inject(s)
            e.controller.requestSave()
            assertEquals(SaveStatus.UNSAVED, e.view.state!!.status); assertTrue(e.controller.isDirty)
            assertEquals("Not saved", e.view.errors.single().first)
            assertFalse(e.view.messages.any { it.startsWith("Saved") })
            assertEquals("orig changed", e.view.text)
            assertEquals("draft must hold the unsaved text after a failed save", "orig changed", e.draftText())
        }
    }

    @Test fun failedSaveAsAndExportReportFailure() {
        val e = env()
        e.view.type("data"); e.controller.requestSaveAs()
        val uri = e.storage.create("x.txt"); e.storage.failOpenOutput = IOException("nope")
        e.controller.onCreatePicked(uri)
        assertEquals("Not saved", e.view.errors.last().first); assertNull(e.controller.currentUri)
        assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
        e.controller.requestExport(); e.controller.onCreatePicked(uri)
        assertEquals("Export failed", e.view.errors.last().first)
    }

    @Test fun failedOpenKeepsEverythingAsItWas() {
        val s = FakeStorage(); val bad = s.put("bin.txt", byteArrayOf(0x61, 0, 0x62))
        val e = env(storage = s)
        e.view.type("mine")
        e.controller.onOpenPicked(bad)
        assertEquals("Couldn't open file", e.view.errors.single().first)
        assertEquals("mine", e.view.text); assertTrue(e.controller.isDirty); assertEquals(1, e.controller.tabCount)
        e.controller.onOpenPicked("content://gone/1"); assertEquals("mine", e.view.text); assertEquals(1, e.controller.tabCount)
        e.controller.onPause(); assertEquals("mine", e.draftText())
    }

    @Test fun draftIsWrittenAfterDelayAndRestoredOnNextLaunch() {
        val dir = File(tmp.root, "shared"); val s = FakeStorage(); val uri = s.put("doc.md", "base".toByteArray())
        val r1 = TestRunner(); val e1 = Env(s, dir, r1); e1.controller.start()
        e1.controller.onOpenPicked(uri); e1.view.type(" + unsaved")
        assertNull("not written before the delay", e1.draftText())
        r1.fireTimers()
        assertEquals("base + unsaved", e1.draftText()); assertTrue(e1.view.state!!.draftBackedUp)
        val e2 = Env(s, dir); e2.controller.start()             // "process death": brand-new controller over the same directory
        assertEquals("base + unsaved", e2.view.text); assertTrue(e2.controller.isDirty); assertEquals(uri, e2.controller.currentUri)
        assertEquals(SaveStatus.UNSAVED, e2.view.state!!.status); assertTrue(e2.view.messages.any { it.contains("Recovered") })
        e2.controller.requestSave()
        assertEquals("base + unsaved", String(s.files[uri]!!))
    }

    @Test fun onPauseFlushesImmediately() {
        val e = env(); e.view.type("typed just before home button")
        assertNull(e.draftText()); e.controller.onPause()
        assertEquals("typed just before home button", e.draftText())
    }

    @Test fun savedDocumentLeavesNoDraftAndStaleTimerCannotRecreateOne() {
        val r = TestRunner(); val e = env(r)
        e.view.type("x"); e.controller.requestSave(); e.controller.onCreatePicked(e.storage.create("a.txt"))
        r.fireTimers(); e.controller.onPause()
        assertNull(e.draftText()); assertTrue(e.session.listTextIds().isEmpty())
    }

    @Test fun editsMadeWhileSavingKeepDocumentUnsaved() {
        val r = TestRunner(deferIo = true); val s = FakeStorage(); val uri = s.put("a.txt", "v1".toByteArray())
        val e = env(r, s)
        e.controller.onOpenPicked(uri); r.drain()
        e.view.type("-v2"); e.controller.requestSave()
        assertEquals(SaveStatus.SAVING, e.view.state!!.status)
        e.view.type("-v3")                                     // typed while the write is in flight
        r.drain()
        assertEquals("v1-v2", String(s.files[uri]!!))
        assertEquals("must not claim the newer text is saved", SaveStatus.UNSAVED, e.view.state!!.status)
        e.controller.requestSave(); r.drain()
        assertEquals("v1-v2-v3", String(s.files[uri]!!)); assertEquals(SaveStatus.SAVED, e.view.state!!.status)
    }

    @Test fun readOnlyFileRedirectsSaveToSaveAs() {
        val s = FakeStorage(); val uri = s.put("ro.md", "ro".toByteArray()); s.readOnly.add(uri)
        val e = env(storage = s)
        e.controller.onOpenPicked(uri)
        assertTrue(e.view.state!!.readOnly)
        e.view.type("!"); e.controller.requestSave()
        assertEquals(1, e.view.createLaunches.size)
        val copy = s.create("ro-copy.md"); e.controller.onCreatePicked(copy)
        assertEquals("ro!", String(s.files[copy]!!)); assertEquals("ro", String(s.files[uri]!!)); assertFalse(e.view.state!!.readOnly)
    }

    @Test fun deletedOrMovedFileFailsSaveWithoutLosingText() {
        val s = FakeStorage(); val uri = s.put("a.txt", "x".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(uri); e.view.type("y"); s.files.remove(uri)
        e.controller.requestSave()
        assertEquals("Not saved", e.view.errors.single().first); assertTrue(e.view.errors.single().second.contains("not found"))
        assertEquals("xy", e.view.text); assertTrue(e.controller.isDirty)
    }

    @Test fun backButtonFlows() {
        val clean = env(); clean.controller.requestExit(); assertTrue(clean.view.finished)

        val d = env(); d.view.type("u"); d.view.exitChoice = "cancel"
        d.controller.requestExit()
        assertFalse(d.view.finished); assertEquals(1, d.view.exitCalls); assertEquals("u", d.view.text)

        d.view.exitChoice = "discard"; d.controller.requestExit()
        assertTrue(d.view.finished); assertNull(d.draftText()); assertEquals("", d.view.text); assertFalse(d.controller.isDirty)
        d.controller.onPause(); assertNull("a discarded draft must not come back on pause", d.draftText())

        val k = env(); k.view.type("keep"); k.view.exitChoice = "keep"; k.controller.requestExit()
        assertTrue(k.view.finished); assertEquals("keep", k.draftText())
    }

    @Test fun interruptedPickerNeverWritesAndKeepsDraft() {
        val dir = File(tmp.root, "x"); val s = FakeStorage()
        val e1 = Env(s, dir); e1.controller.start()
        e1.view.type("important"); e1.controller.requestSaveAs(); e1.controller.onPause()
        val e2 = Env(s, dir); e2.controller.start()             // app restarted while the picker was open
        val uri = s.create("late.txt"); e2.controller.onCreatePicked(uri)
        assertEquals("Save interrupted", e2.view.errors.single().first)
        assertEquals(0, s.files[uri]!!.size); assertEquals("important", e2.view.text)
    }

    @Test fun crlfAndBomFilesKeepTheirFormatThroughEditAndSave() {
        val s = FakeStorage(); val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val uri = s.put("w.txt", bom + "a\r\nb\r\n".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(uri)
        assertEquals("a\nb\n", e.view.text); assertEquals(LineEnding.CRLF, e.view.state!!.lineEnding)
        e.view.type("c\n"); e.controller.requestSave()
        assertArrayEquals(bom + "a\r\nb\r\nc\r\n".toByteArray(), s.files[uri])
    }

    @Test fun mixedLineEndingsAreAnnouncedWhenOpening() {
        val s = FakeStorage(); val uri = s.put("m.txt", "a\r\nb\nc\r\n".toByteArray())
        val e = env(storage = s); e.controller.onOpenPicked(uri)
        assertTrue(e.view.messages.any { it.contains("Mixed line endings") })
    }

    @Test fun emptyDocumentSavesAsEmptyFile() {
        val e = env(); e.controller.requestSaveAs()
        val uri = e.storage.create("empty.md"); e.controller.onCreatePicked(uri)
        assertEquals(0, e.bytes(uri).size); assertEquals(SaveStatus.SAVED, e.view.state!!.status)
        val e2 = env(storage = e.storage); e2.controller.onOpenPicked(uri); assertEquals("", e2.view.text)
    }

    @Test fun recentsRecordOpenedAndSavedFiles() {
        val s = FakeStorage(); val uri = s.put("r.md", "x".toByteArray())
        val e = env(storage = s); e.controller.onOpenPicked(uri)
        assertEquals(uri to "r.md", e.recents.added.last())
    }

    @Test fun recreatedActivityKeepsTextCursorAndUndoHistory() {
        val e = env()
        e.view.type("hello"); e.view.type(" world"); e.view.select(2, 4)
        e.controller.detach()                                   // activity destroyed (e.g. system theme change)
        val v2 = FakeView(); v2.controller = e.controller
        e.controller.attach(v2)                                 // new activity instance
        assertEquals("hello world", v2.text); assertEquals(2, v2.selS); assertEquals(4, v2.selE)
        assertTrue(v2.state!!.canUndo)
        e.controller.undo(); assertEquals("", v2.text)
    }

    @Test fun failedSaveRetainsUnsavedDraftInSession() {
        val s = FakeStorage(); val uri = s.put("doc.txt", "orig text".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(uri)
        e.view.type(" + edits")
        e.controller.onPause() // ensure draft written
        assertEquals("orig text + edits", e.draftText())

        s.failOpenOutput = IOException("permission revoked")
        e.controller.requestSave()

        assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
        assertTrue(e.controller.isDirty)
        assertEquals("orig text + edits", e.draftText())
        assertEquals("Not saved", e.view.errors.last().first)
        assertFalse(e.view.messages.any { it.startsWith("Saved") })
    }

    @Test fun failedSaveWhenRestorationFailsRetainsDraftAndReportsError() {
        val s = FakeStorage(); val uri = s.put("fail_res.txt", "base content".toByteArray())
        var calls = 0
        val flawedStorage = object : DocumentStorage by s {
            override fun openOutput(uri: String): java.io.OutputStream {
                calls++
                throw IOException("storage output failed call $calls")
            }
        }
        val dir = File(tmp.root, "flawed_save_dir")
        val runner = TestRunner()
        val session = SessionStore(dir)
        val recents = TestRecents()
        val view = FakeView()
        val controller = EditorController(DocumentRepository(flawedStorage), session, recents, runner, { IndentSettings() })
        view.controller = controller
        controller.attach(view)
        controller.start()

        controller.onOpenPicked(uri)
        view.type(" + extra")
        controller.onPause()

        controller.requestSave()

        assertEquals(SaveStatus.UNSAVED, view.state!!.status)
        assertEquals("base content + extra", session.loadText(controller.tabId(0)!!)?.toString())
        assertEquals("Not saved", view.errors.last().first)
    }

    @Test fun corruptedSessionFilesRecoverGracefully() {
        val dir = File(tmp.root, "corrupted_session")
        dir.mkdirs()
        // Write corrupted meta file
        File(dir, "session.meta").writeText("NOT_VALID_JSON_OR_META")
        File(dir, "tab-1.text").writeText("orphaned text")

        val s = FakeStorage()
        val e = Env(s, dir); e.controller.start()

        // Should recover without crash, opening a blank new document
        assertEquals(1, e.controller.tabCount)
        assertEquals("", e.view.text)
    }

    @Test fun unicodeDraftBackupAndSessionRestore() {
        val dir = File(tmp.root, "unicode_session")
        val s = FakeStorage()
        val r1 = TestRunner()
        val e1 = Env(s, dir, r1); e1.controller.start()

        val unicodeText = "Unicode: 😀 日本語 ê ë \uD83D\uDE00"
        e1.view.type(unicodeText)
        r1.fireTimers()
        assertEquals(unicodeText, e1.draftText())

        // Simulate app restart / recovery
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals(unicodeText, e2.view.text)
        assertTrue(e2.controller.isDirty)
    }
}
