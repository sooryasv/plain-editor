package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TabsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var n = 0
    private fun env(runner: TestRunner = TestRunner(), storage: FakeStorage = FakeStorage(), dir: File = File(tmp.root, "s${n++}")) =
        Env(storage, dir, runner).also { it.controller.start() }

    private fun Env.tabId(i: Int) = controller.tabId(i)!!

    @Test fun tabsHaveIndependentTextCursorScrollAndUndo() {
        val e = env()
        e.view.type("one one one"); e.view.select(4, 7); e.controller.onScrolled(2)
        e.controller.requestNew()
        assertEquals(2, e.controller.tabCount); assertEquals(1, e.controller.activeIndex); assertEquals("", e.view.text)
        assertFalse("new tab has its own (empty) history", e.view.state!!.canUndo)
        e.view.type("two"); e.view.type(" two")
        e.controller.selectTab(e.tabId(0))
        assertEquals("one one one", e.view.text); assertEquals(4, e.view.selS); assertEquals(7, e.view.selE); assertEquals(2, e.view.top)
        e.controller.undo()
        assertEquals("", e.view.text)                         // tab 0 undone ...
        assertEquals("two two", e.controller.textOf(1))     // ... tab 1 untouched
        e.controller.selectTab(e.tabId(1))
        assertEquals("two two", e.view.text); assertTrue(e.view.state!!.canUndo)
        e.controller.undo(); assertEquals("", e.view.text)
        e.controller.selectTab(e.tabId(0)); assertTrue("redo of tab 0 survived switching", e.view.state!!.canRedo)
        e.controller.redo(); assertEquals("one one one", e.view.text)
        e.assertInSync()
    }

    @Test fun eachTabShowsItsOwnSaveStatus() {
        val s = FakeStorage(); val a = s.put("a.md", "A".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(a); e.controller.requestNew(); e.view.type("B")
        val tabs = e.view.state!!.tabs
        assertEquals(listOf("a.md", "Untitled.txt"), tabs.map { it.name })
        assertEquals(listOf(false, true), tabs.map { it.dirty }); assertEquals(listOf(false, true), tabs.map { it.active })
        e.controller.selectTab(e.tabId(0))
        assertEquals(SaveStatus.SAVED, e.view.state!!.status)
        e.controller.selectTab(e.tabId(1)); assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
    }

    @Test fun tenTabsAreAllowedAndTheElevenIsRefusedWithoutLosingAnything() {
        val s = FakeStorage(); val e = env(storage = s)
        e.view.type("first")
        repeat(9) { e.controller.requestNew() }
        e.view.type("last tab is not blank")
        assertEquals(10, e.controller.tabCount); assertFalse(e.view.state!!.canNewTab)
        e.controller.requestNew()
        assertEquals(10, e.controller.tabCount); assertEquals("Too many tabs", e.view.errors.last().first)
        val f = s.put("x.md", "x".toByteArray()); e.controller.onOpenPicked(f)
        assertEquals(10, e.controller.tabCount); assertEquals(2, e.view.errors.size)
        assertEquals("first", e.controller.textOf(0))
        assertEquals(setOf("Untitled.txt") + (2..9).map { "Untitled $it.txt" }, e.controller.tabNames.take(9).toSet())
        assertEquals(10, e.controller.tabNames.toSet().size)
    }

    @Test fun openingTheSameFileSelectsItsTabInsteadOfDuplicating() {
        val s = FakeStorage(); val a = s.put("a.md", "A".toByteArray()); val b = s.put("b.md", "B".toByteArray())
        val e = env(storage = s)
        e.controller.onOpenPicked(a); e.controller.onOpenPicked(b)
        assertEquals(2, e.controller.tabCount); assertEquals(1, e.controller.activeIndex)
        e.controller.onOpenPicked(a)
        assertEquals(2, e.controller.tabCount); assertEquals(0, e.controller.activeIndex); assertTrue(e.view.messages.last().contains("already open"))
    }

    @Test fun pristineBlankTabIsReusedButEditedBlankTabIsNot() {
        val s = FakeStorage(); val a = s.put("a.md", "A".toByteArray())
        val e1 = env(storage = s); e1.controller.onOpenPicked(a); assertEquals(1, e1.controller.tabCount)
        val e2 = env(storage = s); e2.view.type("x"); e2.controller.onOpenPicked(a); assertEquals(2, e2.controller.tabCount)
        val e3 = env(storage = s); e3.controller.requestNew(); e3.controller.onOpenPicked(a); assertEquals("active blank reused", 2, e3.controller.tabCount)
    }

    @Test fun closingACleanTabNeedsNoPrompt() {
        val e = env(); e.controller.requestNew(); val id = e.tabId(1)
        e.controller.closeTab(id)
        assertEquals(1, e.controller.tabCount); assertEquals(0, e.view.confirmCalls)
    }

    @Test fun closingADirtyTabIsGuardedAndPreservesChanges() {
        val e = env(); e.view.type("precious"); e.controller.requestNew(); e.view.type("other")
        val first = e.tabId(0)
        e.view.confirmChoice = "cancel"; e.controller.closeTab(first)
        assertEquals(2, e.controller.tabCount); assertEquals("the guarded tab becomes visible", 0, e.controller.activeIndex)
        assertEquals("precious", e.view.text); assertEquals(1, e.view.confirmCalls)
        e.view.confirmChoice = "discard"; e.controller.closeTab(first)
        assertEquals(1, e.controller.tabCount); assertEquals("other", e.view.text); assertNull(e.session.loadText(first))
    }

    @Test fun closingADirtyTabWithSaveSavesThenCloses() {
        val e = env(); e.view.type("save me"); e.controller.requestNew(); e.view.type("stay")
        val first = e.tabId(0)
        e.view.confirmChoice = "save"; e.controller.closeTab(first)
        assertEquals("picker for the unsaved tab", 1, e.view.createLaunches.size); assertEquals(2, e.controller.tabCount)
        val uri = e.storage.create("saved.txt"); e.controller.onCreatePicked(uri)
        assertEquals("save me", String(e.bytes(uri))); assertEquals(1, e.controller.tabCount); assertEquals("stay", e.view.text)
    }

    @Test fun cancellingThePickerWhileClosingKeepsTheTab() {
        val e = env(); e.view.type("keep"); e.controller.requestNew()
        e.view.confirmChoice = "save"; e.controller.closeTab(e.tabId(0)); e.controller.onCreatePicked(null)
        assertEquals(2, e.controller.tabCount); assertEquals("keep", e.controller.textOf(0)); assertTrue(e.controller.isDirty(0))
    }

    @Test fun closingTheLastTabLeavesAFreshBlankOne() {
        val e = env(); e.view.type("x"); e.view.confirmChoice = "discard"
        e.controller.closeTab(e.tabId(0))
        assertEquals(1, e.controller.tabCount); assertEquals("", e.view.text); assertEquals(SaveStatus.NEW, e.view.state!!.status)
    }

    @Test fun savingOneTabLeavesOtherTabsUnsaved() {
        val e = env(); e.view.type("a"); e.controller.requestNew(); e.view.type("b")
        e.controller.requestSave(); e.controller.onCreatePicked(e.storage.create("b.txt"))
        assertFalse(e.controller.isDirty(1)); assertTrue(e.controller.isDirty(0))
        e.controller.onPause(); assertEquals("a", e.draftText(0)); assertNull(e.draftText(1))
    }

    // ----------------------------------------------------------------------------------------- session restore

    @Test fun wholeWorkspaceIsRestoredAfterARestart() {
        val dir = File(tmp.root, "sess"); val s = FakeStorage()
        val a = s.put("a.md", "# A\n".toByteArray()); val c = s.put("c.py", "print(1)\n".toByteArray())
        val e1 = Env(s, dir); e1.controller.start()
        e1.controller.onOpenPicked(a)                                     // clean, file-backed
        e1.controller.requestNew(); e1.view.type("draft B"); e1.view.select(3, 5); e1.controller.onScrolled(1)   // unsaved, untitled
        e1.controller.onOpenPicked(c); e1.view.type("# edited\n")        // file-backed + unsaved
        e1.controller.selectTab(e1.tabId(1)); e1.controller.onPause()
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals(listOf("a.md", "Untitled.txt", "c.py"), e2.controller.tabNames)
        assertEquals("active tab restored", 1, e2.controller.activeIndex)
        assertEquals("draft B", e2.view.text); assertEquals(3, e2.view.selS); assertEquals(5, e2.view.selE)
        assertNull("clean tabs are loaded lazily", e2.controller.textOf(0)); assertNull(e2.controller.textOf(2))
        e2.controller.selectTab(e2.tabId(2))
        assertEquals("print(1)\n# edited\n", e2.view.text)
        assertTrue(e2.controller.isDirty(2)); assertEquals(c, e2.controller.currentUri)
        e2.controller.selectTab(e2.tabId(0)); assertEquals("# A\n", e2.view.text); assertFalse(e2.controller.isDirty(0))
        assertEquals(SaveStatus.SAVED, e2.view.state!!.status)
    }

    @Test fun restoredCleanTabWhoseFileIsGoneIsClosedWithAMessage() {
        val dir = File(tmp.root, "gone"); val s = FakeStorage(); val a = s.put("a.md", "A".toByteArray())
        val e1 = Env(s, dir); e1.controller.start(); e1.controller.onOpenPicked(a); e1.controller.requestNew(); e1.view.type("kept")
        e1.controller.selectTab(e1.tabId(0)); e1.controller.onPause()
        s.files.remove(a)
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals("Couldn't restore “a.md”", e2.view.errors.single().first)
        assertEquals(listOf("Untitled.txt"), e2.controller.tabNames); assertEquals("kept", e2.view.text)
    }

    @Test fun unsavedTabsSurviveEvenIfTheirFileDisappears() {
        val dir = File(tmp.root, "gone2"); val s = FakeStorage(); val a = s.put("a.md", "A".toByteArray())
        val e1 = Env(s, dir); e1.controller.start(); e1.controller.onOpenPicked(a); e1.view.type("+edit"); e1.controller.onPause()
        s.files.remove(a)
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals("A+edit", e2.view.text); assertTrue(e2.view.errors.isEmpty())
        e2.controller.requestSave(); assertEquals("Not saved", e2.view.errors.single().first); assertEquals("A+edit", e2.view.text)
    }

    @Test fun orphanDraftWithoutSessionFileIsRecovered() {
        val dir = File(tmp.root, "orph"); val s = FakeStorage()
        val e1 = Env(s, dir); e1.controller.start(); e1.view.type("work"); e1.controller.onPause()
        File(dir, "session.meta").delete()
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals("work", e2.view.text); assertTrue(e2.controller.tabNames.single().startsWith("Recovered draft"))
    }

    @Test fun corruptSessionMetaStartsFreshWithoutCrashing() {
        val dir = File(tmp.root, "corrupt"); dir.mkdirs(); File(dir, "session.meta").writeText("\u0000\u0001 not a properties file \\u00zz")
        val e = Env(FakeStorage(), dir); e.controller.start()
        assertEquals(1, e.controller.tabCount); assertEquals("", e.view.text)
    }

    // ------------------------------------------------------------------------------------------ undo persistence

    @Test fun undoHistoryOfAnUnsavedTabSurvivesARestart() {
        val dir = File(tmp.root, "undo"); val s = FakeStorage()
        val r = TestRunner(); val e1 = Env(s, dir, r); e1.controller.start()
        e1.view.type("alpha\n"); e1.view.type("beta\n"); e1.view.type("gamma\n")
        e1.controller.replaceAll("a", "A", true, false)
        e1.controller.onPause()
        val before = e1.controller.activeText()
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals(before, e2.view.text); assertTrue(e2.view.state!!.canUndo); assertFalse("redo is not persisted", e2.view.state!!.canRedo)
        e2.controller.undo(); assertEquals("alpha\nbeta\ngamma\n", e2.view.text)      // the bulk Replace All is undone first
        e2.controller.undo(); assertEquals("alpha\nbeta\n", e2.view.text)
        e2.assertInSync()
    }

    @Test fun cleanTabsStartWithEmptyHistoryAfterARestart() {
        val dir = File(tmp.root, "undo2"); val s = FakeStorage(); val uri = s.put("a.txt", "x".toByteArray())
        val e1 = Env(s, dir); e1.controller.start(); e1.controller.onOpenPicked(uri); e1.view.type("y"); e1.controller.requestSave(); e1.controller.onPause()
        assertTrue(e1.view.state!!.canUndo)
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals("xy", e2.view.text); assertFalse(e2.view.state!!.canUndo)
    }

    @Test fun historyIsDroppedWhenTheRestoredTextDoesNotMatchIt() {
        val dir = File(tmp.root, "undo3"); val s = FakeStorage()
        val e1 = Env(s, dir); e1.controller.start(); e1.view.type("hello"); e1.controller.onPause()
        val id = e1.tabId(0); val hist = File(dir, "tab-$id.hist").readBytes()
        e1.view.type(" world"); e1.controller.onPause()                       // text changes, then the OLD history file is put back
        File(dir, "tab-$id.hist").writeBytes(hist)
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals("hello world", e2.view.text); assertFalse("history for different text is rejected", e2.view.state!!.canUndo)
    }

    @Test fun corruptHistoryFileIsIgnored() {
        val dir = File(tmp.root, "undo4"); val s = FakeStorage()
        val e1 = Env(s, dir); e1.controller.start(); e1.view.type("data"); e1.controller.onPause()
        File(dir, "tab-${e1.tabId(0)}.hist").writeBytes(ByteArray(40) { 7 })
        val e2 = Env(s, dir); e2.controller.start()
        assertEquals("data", e2.view.text); assertFalse(e2.view.state!!.canUndo)
    }

    @Test fun editorModelMismatchResynchronisesAndResetsUndoInsteadOfCorrupting() {
        val e = env(); e.view.type("abc")
        e.view.text = "hacked behind the model's back"                         // the editor now disagrees with the model
        e.controller.onWindowEdit(0, "WRONG", "x", e.view.text.length)
        assertEquals("hacked behind the model's back", e.controller.activeText())
        assertTrue(e.view.messages.any { it.contains("re-synchronised") }); assertFalse(e.view.state!!.canUndo)
    }

    // ---------------------------------------------------------------------------------------- Open With / external

    @Test fun externalOpenWithWriteAccessIsEditable() {
        val s = FakeStorage(); val u = s.put("n.md", "ext".toByteArray()); val e = env(storage = s)
        e.controller.openExternal(u, writable = true)
        assertEquals("ext", e.view.text); assertFalse(e.view.state!!.readOnly)
        e.view.type("!"); e.controller.requestSave(); assertEquals("ext!", String(s.files[u]!!))
    }

    @Test fun externalOpenWithoutWriteAccessIsReadOnlyAndSaveGoesToSaveAs() {
        val s = FakeStorage(); val u = s.put("n.md", "ext".toByteArray()); val e = env(storage = s)
        e.controller.openExternal(u, writable = false)
        assertTrue(e.view.state!!.readOnly); assertTrue(e.view.messages.any { it.contains("read-only") })
        e.view.type("!"); e.controller.requestSave()
        assertEquals(1, e.view.createLaunches.size); assertEquals("ext", String(s.files[u]!!))
    }

    @Test fun externalOpenNeverTouchesExistingDirtyTabs() {
        val s = FakeStorage(); val u = s.put("n.md", "ext".toByteArray()); val e = env(storage = s)
        e.view.type("my unsaved work"); e.controller.openExternal(u, true)
        assertEquals(2, e.controller.tabCount); assertEquals("my unsaved work", e.controller.textOf(0)); assertTrue(e.controller.isDirty(0))
        assertEquals(0, e.view.confirmCalls); assertEquals("ext", e.view.text)
    }

    @Test fun externalOpenOfAnAlreadyOpenFileJustSwitchesToIt() {
        val s = FakeStorage(); val u = s.put("n.md", "ext".toByteArray()); val e = env(storage = s)
        e.controller.openExternal(u, true); e.controller.requestNew(); e.view.type("zzz")
        e.controller.openExternal(u, true)
        assertEquals(2, e.controller.tabCount); assertEquals("ext", e.view.text)
    }

    @Test fun externalOpenWhenAllTenTabsAreUsedIsRefusedSafely() {
        val s = FakeStorage(); val u = s.put("n.md", "ext".toByteArray()); val e = env(storage = s)
        e.view.type("x"); repeat(9) { e.controller.requestNew() }; e.view.type("y")
        e.controller.openExternal(u, true)
        assertEquals(10, e.controller.tabCount); assertEquals("Too many tabs", e.view.errors.last().first)
    }

    @Test fun externalFilesThatCannotBeReadReportAnErrorAndChangeNothing() {
        val s = FakeStorage(); val e = env(storage = s); e.view.type("mine")
        e.controller.openExternal("content://revoked/1", true)
        assertEquals("Couldn't open file", e.view.errors.single().first); assertEquals(1, e.controller.tabCount); assertEquals("mine", e.view.text)
    }

    @Test fun externalFileOpensAtColdStartAfterSessionRestore() {
        val dir = File(tmp.root, "cold"); val s = FakeStorage(); val u = s.put("v.md", "viewed".toByteArray())
        val e1 = Env(s, dir); e1.controller.start(); e1.view.type("old work"); e1.controller.onPause()
        val e2 = Env(s, dir); e2.controller.start(); e2.controller.openExternal(u, true)
        assertEquals(listOf("Untitled.txt", "v.md"), e2.controller.tabNames); assertEquals("viewed", e2.view.text)
        assertEquals("old work", e2.controller.textOf(0))
    }

    // ------------------------------------------------------------------------------------------------------ PDF

    private val pdfBytes = ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n").toByteArray()

    @Test fun aPdfOpensInTheViewerNeverAsText() {
        val s = FakeStorage(); val u = s.put("doc.pdf", pdfBytes, "application/pdf"); val e = env(storage = s)
        e.controller.onOpenPicked(u)
        assertEquals(DocKind.PDF, e.view.state!!.kind); assertEquals(listOf(u, "doc.pdf", 0, 1f), e.view.pdfShown.last())
        assertNull(e.controller.activeText()); assertEquals("blank tab reused", 1, e.controller.tabCount)
        assertTrue(e.view.state!!.readOnly); assertFalse(e.view.state!!.canUndo)
    }

    @Test fun pdfDetectionUsesTheContentNotTheName() {
        val s = FakeStorage(); val u = s.put("renamed.txt", pdfBytes); val e = env(storage = s)
        e.controller.onOpenPicked(u)
        assertEquals(DocKind.PDF, e.view.state!!.kind)
    }

    @Test fun aFileNamedPdfWithoutPdfContentIsRefusedNotShownAsText() {
        val s = FakeStorage(); val u = s.put("fake.pdf", "just some text".toByteArray()); val e = env(storage = s)
        e.view.type("mine"); e.controller.onOpenPicked(u)
        assertEquals("Couldn't open file", e.view.errors.single().first); assertTrue(e.view.errors.single().second.contains("not a valid PDF"))
        assertEquals(1, e.controller.tabCount); assertEquals("mine", e.view.text)
    }

    @Test fun aLargePdfBeyondTheTextLimitStillOpensInTheViewer() {
        val s = FakeStorage(); val big = pdfBytes + ByteArray(25 * 1024 * 1024) { 1 }
        val u = s.put("big.pdf", big); val e = env(storage = s)
        e.controller.onOpenPicked(u); assertEquals(DocKind.PDF, e.view.state!!.kind)
    }

    @Test fun pdfTabsCannotBeSavedOrEditedButCanBeClosedWithoutPrompt() {
        val s = FakeStorage(); val u = s.put("doc.pdf", pdfBytes); val e = env(storage = s)
        e.view.type("text"); e.controller.onOpenPicked(u)
        e.controller.requestSave(); e.controller.requestSaveAs(); e.controller.requestExport()
        assertEquals(0, e.view.createLaunches.size); assertTrue(e.view.messages.count { it.contains("view-only") } >= 3)
        e.controller.undo(); e.controller.tabKey(); e.controller.replaceAll("a", "b", true, false)  // no-ops on a PDF tab
        assertArrayEquals(pdfBytes, s.files[u]); assertFalse(e.controller.isDirty(1))
        e.controller.closeTab(e.tabId(1)); assertEquals(0, e.view.confirmCalls); assertEquals("text", e.view.text)
    }

    @Test fun pdfPageAndZoomAreRememberedPerTabAcrossSwitchesAndRestarts() {
        val dir = File(tmp.root, "pdf"); val s = FakeStorage(); val u = s.put("doc.pdf", pdfBytes)
        val e1 = Env(s, dir); e1.controller.start(); e1.view.type("t"); e1.controller.onOpenPicked(u)
        e1.controller.onPdfState(7, 2.0f); e1.controller.selectTab(e1.tabId(0)); e1.controller.selectTab(e1.tabId(1))
        assertEquals(listOf(u, "doc.pdf", 7, 2.0f), e1.view.pdfShown.last())
        e1.controller.onPause()
        val e2 = Env(s, dir); e2.controller.start()
        e2.controller.selectTab(e2.tabId(1))
        assertEquals(listOf(u, "doc.pdf", 7, 2.0f), e2.view.pdfShown.last())
        e2.controller.selectTab(e2.tabId(0)); assertEquals("t", e2.view.text)
    }

    @Test fun pdfTabsCountTowardsTheTabLimit() {
        val s = FakeStorage(); val u = s.put("doc.pdf", pdfBytes); val e = env(storage = s)
        e.view.type("x"); repeat(8) { e.controller.requestNew() }; e.view.type("y"); e.controller.onOpenPicked(u)
        assertEquals(10, e.controller.tabCount)
        e.controller.requestNew(); assertEquals("Too many tabs", e.view.errors.last().first)
    }

    @Test fun nextAndPreviousTabCycle() {
        val e = env(); e.controller.requestNew(); e.controller.requestNew()
        assertEquals(2, e.controller.activeIndex); e.controller.nextTab(1); assertEquals(0, e.controller.activeIndex)
        e.controller.nextTab(-1); assertEquals(2, e.controller.activeIndex)
    }
}
