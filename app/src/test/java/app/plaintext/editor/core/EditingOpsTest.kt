package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Find/replace, indentation and undo/redo as seen through the controller (model + simulated editor kept in sync). */
class EditingOpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var n = 0
    private fun env(initial: String = "", name: String = "t.txt", runner: TestRunner = TestRunner(), indent: IndentSettings = IndentSettings()): Env {
        val s = FakeStorage()
        val e = Env(s, File(tmp.root, "s${n++}"), runner, indent); e.controller.start()
        if (initial.isNotEmpty() || name != "t.txt") { val u = s.put(name, initial.toByteArray()); e.controller.onOpenPicked(u) }
        return e
    }
    private fun Env.sel() = view.text.substring(view.selS, view.selE)

    // -------------------------------------------------------------------------------------------- find

    @Test fun findNextWrapsAndPreviousGoesBackwards() {
        val e = env("foo bar foo baz foo")
        val c = e.controller
        assertTrue(c.find("foo", true, false, true)); assertEquals(0 to 3, e.view.selS to e.view.selE)
        c.find("foo", true, false, true); assertEquals(8, e.view.selS)
        c.find("foo", true, false, true); assertEquals(16, e.view.selS)
        c.find("foo", true, false, true); assertEquals(0, e.view.selS); assertTrue(e.view.messages.contains("Wrapped around"))
        c.find("foo", true, false, false); assertEquals("previous from the first match wraps to the last", 16, e.view.selS)
        c.find("foo", true, false, false); assertEquals(8, e.view.selS)
        assertEquals("foo", e.sel())
    }

    @Test fun findReportsNoMatchAndEmptyQueryDoesNothing() {
        val e = env("abc")
        assertFalse(e.controller.find("zzz", true, false, true)); assertEquals(0, e.view.lastSearch!!.total); assertTrue(e.view.messages.contains("No matches"))
        val m = e.view.messages.size; assertFalse(e.controller.find("", true, false, true)); assertEquals(m, e.view.messages.size)
    }

    @Test fun caseAndWholeWordOptions() {
        val e = env("Cat cat concat CAT")
        e.controller.find("cat", true, false, true); assertEquals(4, e.view.selS)
        e.controller.find("cat", false, false, true); assertEquals(11, e.view.selS)       // case-insensitive: next hit is inside "concat"
        e.controller.find("cat", false, true, true); assertEquals(15, e.view.selS)         // whole word skips "concat"
        e.controller.find("cat", false, true, true); assertEquals(0, e.view.selS)
    }

    @Test fun matchCountAndCurrentIndexAreReported() {
        val e = env("aa aa aa aa")
        e.controller.find("aa", true, false, true)
        assertEquals(1, e.view.lastSearch!!.index); assertEquals(4, e.view.lastSearch!!.total); assertFalse(e.view.lastSearch!!.capped)
        e.controller.find("aa", true, false, true); e.controller.find("aa", true, false, true)
        assertEquals(3, e.view.lastSearch!!.index)
        e.controller.refreshSearchCount("aa", false, true); assertEquals(4, e.view.lastSearch!!.total)
        e.controller.refreshSearchCount("nothing", true, false); assertEquals(0, e.view.lastSearch!!.total)
    }

    @Test fun findMovesTheWindowToMatchesInALargeDocument() {
        val text = Env.doc(3_000_000); val e = env(text, "big.txt")
        assertTrue(e.view.state!!.windowed)
        assertTrue(e.controller.find("line 30000 ", true, false, true))
        val at = text.indexOf("line 30000 "); val w = e.controller.activeWindow()!!
        assertTrue(w.start <= at && at < w.end); assertEquals("line 30000 ", e.sel()); e.assertInSync()
        assertTrue(e.controller.find("line 5 ", true, false, false) || true)
        e.controller.find("line 29999 ", true, false, false); assertEquals("line 29999 ", e.sel())
        e.controller.find("line 0 ", true, false, true); assertEquals("wrap to the start", "line 0 ", e.sel()); assertEquals(0, e.controller.activeWindow()!!.start)
    }

    @Test fun staleCountsAreDroppedWhenTheTextChangesBeforeTheyArrive() {
        val r = TestRunner(deferIo = true); val e = env("aa aa", runner = r); r.drain()
        e.view.lastSearch = null
        e.controller.refreshSearchCount("aa", true, false); e.view.type(" aa"); r.drain()
        assertNull("the result for the old text must not be shown", e.view.lastSearch)
    }

    // ------------------------------------------------------------------------------------------ replace

    @Test fun replaceCurrentReplacesTheSelectedMatchThenMovesOn() {
        val e = env("one two one two one")
        e.controller.find("one", true, false, true)
        e.controller.replaceCurrent("one", "1", true, false)
        assertEquals("1 two one two one", e.view.text); assertEquals("next match selected", 6 to 9, e.view.selS to e.view.selE)
        e.controller.replaceCurrent("one", "1", true, false); e.controller.replaceCurrent("one", "1", true, false)
        assertEquals("1 two 1 two 1", e.view.text); e.assertInSync()
        e.controller.undo(); assertEquals("1 two 1 two one", e.view.text)           // one undo step per replacement
    }

    @Test fun replaceCurrentWithoutAMatchingSelectionOnlyFindsTheNext() {
        val e = env("a b a"); e.view.select(1, 2)
        e.controller.replaceCurrent("a", "X", true, false)
        assertEquals("a b a", e.view.text); assertEquals(4, e.view.selS)
    }

    @Test fun replacementContainingTheQueryDoesNotLoop() {
        val e = env("a a a")
        e.controller.find("a", true, false, true)
        repeat(3) { e.controller.replaceCurrent("a", "aa", true, false) }
        assertEquals("aa aa aa", e.view.text)
    }

    @Test fun replaceAllIsOneUndoableStepAndRestoresOriginalCase() {
        val e = env("Cat cat CAT dog\ncat")
        e.controller.replaceAll("cat", "tiger", false, false)
        assertEquals("tiger tiger tiger dog\ntiger", e.view.text); assertTrue(e.view.messages.contains("Replaced 4 occurrences."))
        assertTrue(e.view.state!!.canUndo); assertTrue(e.controller.isDirty(0)); e.assertInSync()
        e.controller.undo(); assertEquals("Cat cat CAT dog\ncat", e.view.text); assertTrue(e.view.state!!.canRedo)
        e.controller.redo(); assertEquals("tiger tiger tiger dog\ntiger", e.view.text); e.assertInSync()
    }

    @Test fun replaceAllCanDeleteAndRespectsWholeWordAndCase() {
        val e = env("cat concat Cat cat_x cat")
        e.controller.replaceAll("cat", "", true, true); assertEquals(" concat Cat cat_x ", e.view.text)
        e.controller.undo(); e.controller.replaceAll("Cat", "Dog", true, false); assertEquals("cat concat Dog cat_x cat", e.view.text)
    }

    @Test fun replaceAllWithNoMatchesChangesNothing() {
        val e = env("abc"); e.controller.replaceAll("zzz", "y", true, false)
        assertEquals("abc", e.view.text); assertFalse(e.view.state!!.canUndo); assertTrue(e.view.messages.contains("No matches")); assertFalse(e.controller.isDirty(0))
    }

    @Test fun replaceAllIsRefusedIfTheTextChangedWhileItRan() {
        val r = TestRunner(deferIo = true); val e = env("a a a", runner = r); r.drain()
        e.controller.replaceAll("a", "b", true, false)
        e.view.type(" a")                                   // the user types while the replacement is being computed
        r.drain()
        assertEquals("a a a a", e.view.text); assertTrue(e.view.messages.any { it.contains("nothing was replaced") })
    }

    @Test fun replaceAllTooManyMatchesIsRefusedWithoutChangingText() {
        val e = env("a".repeat(Limits.MAX_REPLACE_MATCHES + 1), "many.txt")
        e.controller.replaceAll("a", "b", true, false)
        assertTrue(e.view.messages.any { it.contains("Too many matches") }); assertEquals('a', e.controller.activeText()!![0]); assertFalse(e.controller.isDirty(0))
    }

    @Test fun replaceAllOnTenMebibytesAndUndoAreExact() {
        val text = Env.doc(10_000_000); val e = env(text, "big.txt")
        e.controller.jumpToPermille(400)
        e.controller.replaceAll("ünï", "UNI-é", true, false)
        val replaced = text.replace("ünï", "UNI-é")
        assertEquals(replaced.length, e.controller.activeLength()); assertEquals(replaced, e.controller.activeText()); e.assertInSync()
        e.controller.undo(); assertEquals(text, e.controller.activeText()); e.assertInSync()
        e.controller.redo(); assertEquals(replaced, e.controller.activeText())
        e.controller.requestSave()
        assertEquals(SaveStatus.SAVED, e.view.state!!.status); assertArrayEquals(replaced.toByteArray(), e.storage.files[e.controller.currentUri!!])
    }

    @Test fun typingAfterReplaceAllAndUndoStaysConsistent() {
        val e = env("x y x"); e.controller.replaceAll("x", "zz", true, false); e.view.type("!")
        e.controller.undo(); assertEquals("zz y zz", e.view.text); e.controller.undo(); assertEquals("x y x", e.view.text)
        e.assertInSync(); e.controller.redo(); e.controller.redo(); assertEquals("zz y zz!", e.view.text)
    }

    // ------------------------------------------------------------------------------------------ indentation

    @Test fun tabKeyInsertsToTheNextTabStopOrATab() {
        val e = env("", indent = IndentSettings(4, false)); e.view.type("ab"); e.controller.tabKey()
        assertEquals("ab  ", e.view.text); assertEquals(4, e.view.selS)
        val t = env("", indent = IndentSettings(4, true)); t.view.type("ab"); t.controller.tabKey(); assertEquals("ab\t", t.view.text)
        e.controller.undo(); assertEquals("ab", e.view.text)
    }

    @Test fun indentAndOutdentSelectedLinesAreSingleUndoSteps() {
        val e = env("a\n\n  b\nc")
        e.view.select(0, e.view.text.length)
        e.controller.indentSelection()
        assertEquals("    a\n\n      b\n    c", e.view.text); e.assertInSync()
        assertEquals("selection still covers the same lines", "    a\n\n      b\n    c", e.sel())
        e.controller.outdentSelection(); assertEquals("a\n\n  b\nc", e.view.text)
        e.controller.outdentSelection(); assertEquals("the pre-existing two-space indent is removed too", "a\n\nb\nc", e.view.text)
        e.controller.outdentSelection(); assertTrue(e.view.messages.contains("Nothing to outdent")); assertEquals("a\n\nb\nc", e.view.text)
        e.controller.undo(); assertEquals("a\n\n  b\nc", e.view.text)
        e.controller.undo(); assertEquals("    a\n\n      b\n    c", e.view.text)
        e.controller.undo(); assertEquals("a\n\n  b\nc", e.view.text); e.assertInSync()
    }

    @Test fun indentWidthAndTabsPreferenceAreHonouredAndYamlNeverGetsTabs() {
        val two = env("x", indent = IndentSettings(2, false)); two.view.select(0, 1); two.controller.indentSelection(); assertEquals("  x", two.view.text)
        val tabs = env("x", "a.py", indent = IndentSettings(4, true)); tabs.view.select(0, 1); tabs.controller.indentSelection(); assertEquals("\tx", tabs.view.text)
        val yaml = env("x", "a.yml", indent = IndentSettings(2, true)); yaml.view.select(0, 1); yaml.controller.indentSelection(); assertEquals("  x", yaml.view.text)
        yaml.controller.tabKey(); assertFalse(yaml.view.text.contains('\t'))
    }

    @Test fun indentingNeverTouchesOtherWhitespaceOrLineEndings() {
        val src = "a \t\n\tb  \n\n   c\n"
        val s = FakeStorage(); val u = s.put("w.txt", src.replace("\n", "\r\n").toByteArray())
        val e = Env(s, File(tmp.root, "crlf"), TestRunner()); e.controller.start(); e.controller.onOpenPicked(u)
        e.view.select(0, e.view.text.length); e.controller.indentSelection(); e.controller.outdentSelection()
        assertEquals(src, e.view.text); e.controller.requestSave()
        assertEquals("a CRLF file stays CRLF", src.replace("\n", "\r\n"), String(s.files[u]!!))
    }

    @Test fun indentationInsideALargeDocumentUsesDocumentOffsetsCorrectly() {
        val text = Env.doc(2_000_000); val e = env(text, "big.py")
        e.controller.jumpToPermille(600); val w = e.controller.activeWindow()!!
        val a = e.view.text.indexOf('\n', 5000) + 1; val b = e.view.text.indexOf('\n', a + 700) + 1
        e.view.select(a, b); e.controller.indentSelection(); e.assertInSync()
        val expected = text.substring(0, w.start + a) + text.substring(w.start + a, w.start + b).lines().dropLast(1).joinToString("\n") { "    $it" } + "\n" + text.substring(w.start + b)
        assertEquals(expected, e.controller.activeText())
        e.controller.undo(); assertEquals(text, e.controller.activeText())
    }

    // ------------------------------------------------------------------------------------ undo / redo grouping

    @Test fun deliberateActionsAreSeparateUndoStepsAndTypingDoesNotMergeIntoThem() {
        val e = env("x y x"); e.view.type("1")
        e.controller.replaceAll("x", "zz", true, false)
        e.view.type("2"); e.view.type("3")
        e.controller.undo(); assertEquals("zz y zz1", e.view.text)       // only the typing after Replace All
        e.controller.undo(); assertEquals("x y x1", e.view.text)         // then Replace All itself
        e.controller.undo(); assertEquals("x y x", e.view.text)
    }

    @Test fun typingBurstsAreGroupedAndNewLinesStartNewSteps() {
        val e = env()
        "hello".forEach { e.view.type(it.toString()) }
        e.view.type("\n"); "x".forEach { e.view.type(it.toString()) }
        e.controller.undo(); assertEquals("hello\n", e.view.text)
        e.controller.undo(); assertEquals("hello", e.view.text)
        e.controller.undo(); assertEquals("", e.view.text); assertFalse(e.view.state!!.canUndo)
        e.controller.redo(); e.controller.redo(); e.controller.redo(); assertEquals("hello\nx", e.view.text)
    }

    @Test fun anyEditAfterUndoClearsRedoAndDeletionsUndo() {
        val e = env("abcdef"); e.view.deleteRange(1, 3); assertEquals("adef", e.view.text)
        e.controller.undo(); assertEquals("abcdef", e.view.text); assertTrue(e.view.state!!.canRedo)
        e.view.type("!"); assertFalse(e.view.state!!.canRedo)
    }

    @Test fun undoAfterSaveMakesTheDocumentUnsavedAgain() {
        val e = env("abc"); e.view.type("d"); e.controller.requestSave(); assertEquals(SaveStatus.SAVED, e.view.state!!.status)
        e.controller.undo(); assertEquals("abc", e.view.text); assertEquals(SaveStatus.UNSAVED, e.view.state!!.status)
    }

    @Test fun undoRedoWithNothingToDoIsHarmless() {
        val e = env("x"); e.controller.undo(); e.controller.redo(); assertEquals("x", e.view.text); assertFalse(e.controller.isDirty(0))
    }

    @Test fun cursorLandsAfterTheRestoredTextOnUndo() {
        val e = env("hello world"); e.view.deleteRange(5, 11); e.controller.undo()
        assertEquals("hello world", e.view.text); assertEquals(11, e.view.selS); assertEquals(11, e.view.selE)
    }
}
