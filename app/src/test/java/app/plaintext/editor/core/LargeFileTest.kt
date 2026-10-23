package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** Controller-level behaviour with documents far beyond what an EditText could hold (windowing, 10 MiB limit). */
class LargeFileTest {
    @get:Rule val tmp = TemporaryFolder()
    private var n = 0
    private fun env(runner: TestRunner = TestRunner(), storage: FakeStorage = FakeStorage(), dir: File = File(tmp.root, "s${n++}")) =
        Env(storage, dir, runner).also { it.controller.start() }

    private val big by lazy { Env.doc(10_000_000) }                    // ~10.2 MB of UTF-8: inside the 10 MiB limit
    private fun bigEnv(): Pair<Env, String> {
        val s = FakeStorage(); val u = s.put("big.txt", big.toByteArray())
        val e = env(storage = s); e.controller.onOpenPicked(u); return e to u
    }

    @Test fun aTenMebibyteFileOpensAsABoundedWindow() {
        val (e, _) = bigEnv()
        assertTrue(big.toByteArray().size <= Limits.MAX_OPEN_BYTES)
        assertEquals(big.length, e.controller.activeLength()); assertTrue(e.view.state!!.windowed)
        val w = e.controller.activeWindow()!!
        assertEquals(0, w.start); assertTrue("window is bounded: ${w.length}", w.length in Limits.WINDOW_CHARS..(Limits.WINDOW_CHARS + 500))
        assertEquals(big.substring(0, w.end), e.view.text); e.assertInSync()
    }

    @Test fun jumpToBottomAndTopAreSingleWindowSwaps() {
        val (e, _) = bigEnv()
        val shows = e.view.windowShows
        e.controller.jumpBottom()
        assertEquals(shows + 1, e.view.windowShows)
        assertTrue(e.view.text.endsWith(big.takeLast(500))); assertEquals(big.length, e.controller.activeWindow()!!.end)
        assertEquals(e.view.text.length, e.view.selS); assertEquals(1000, e.view.state!!.navPermille.coerceAtLeast(990).coerceAtMost(1000))
        e.assertInSync()
        e.controller.jumpTop()
        assertTrue(e.view.text.startsWith("line 0 ")); assertEquals(0, e.controller.activeWindow()!!.start); assertEquals(0, e.view.selS); e.assertInSync()
        e.controller.jumpTop(); assertTrue("already at the top: no re-layout needed", e.view.edgeScrolls.contains(false))
    }

    @Test fun seekingToTheMiddleShowsTheMiddle() {
        val (e, _) = bigEnv()
        e.controller.jumpToPermille(500)
        val w = e.controller.activeWindow()!!
        assertTrue(w.start < big.length / 2 && big.length / 2 < w.end)
        assertEquals(big.substring(w.start, w.end), e.view.text); e.assertInSync()
        assertTrue(e.view.state!!.navPermille in 450..550)
    }

    @Test fun scrollingThroughTheWholeDocumentKeepsEditorAndModelInSync() {
        val (e, _) = bigEnv()
        var steps = 0
        while (true) {
            val before = e.controller.activeWindow()!!
            e.controller.onScrolled(e.view.text.length - 10)               // reached the bottom edge of the window
            e.controller.requestShift(+1)
            val after = e.controller.activeWindow()!!
            if (after == before) break
            assertTrue(after.start > before.start && after.end > before.end); e.assertInSync(); steps++; assertTrue(steps < 2000)
        }
        assertEquals(big.length, e.controller.activeWindow()!!.end); assertTrue("walked ${steps} steps", steps in 150..400)
        while (true) {
            val before = e.controller.activeWindow()!!
            e.controller.onScrolled(5); e.controller.requestShift(-1)
            if (e.controller.activeWindow() == before) break
            e.assertInSync()
        }
        assertEquals(0, e.controller.activeWindow()!!.start)
    }

    @Test fun editsAnywhereInTheDocumentLandInTheRightPlaceAndSaveExactly() {
        val (e, u) = bigEnv(); val ref = StringBuilder(big); val rnd = Random(4)
        repeat(12) {
            e.controller.jumpToPermille(rnd.nextInt(1001))
            val w = e.controller.activeWindow()!!
            val pos = rnd.nextInt(e.view.text.length)
            val ins = "«edit $it»"
            e.view.typeAt(pos, ins); ref.insert(w.start + pos, ins)
            e.assertInSync()
        }
        e.controller.jumpBottom(); e.view.type("END\n"); ref.append("END\n")
        e.controller.jumpTop(); e.view.typeAt(0, "TOP\n"); ref.insert(0, "TOP\n")
        assertEquals(ref.toString(), e.controller.activeText())
        e.controller.requestSave()
        assertEquals(SaveStatus.SAVED, e.view.state!!.status)
        assertArrayEquals(ref.toString().toByteArray(), e.storage.files[u])
    }

    @Test fun undoAndRedoReachEditsOutsideTheCurrentWindow() {
        val (e, _) = bigEnv()
        e.controller.jumpToPermille(700); val w = e.controller.activeWindow()!!
        e.view.typeAt(50, "<<<MARK>>>"); val edited = e.controller.activeText()!!
        e.controller.jumpTop(); assertFalse(e.view.text.contains("<<<MARK>>>"))
        e.controller.undo()
        assertEquals("the window moved to the undone edit", true, e.controller.activeWindow()!!.contains(w.start + 50, w.start + 50))
        assertFalse(e.controller.activeText()!!.contains("<<<MARK>>>")); assertEquals(big, e.controller.activeText()); e.assertInSync()
        e.controller.jumpBottom(); e.controller.redo()
        assertEquals(edited, e.controller.activeText()); assertTrue(e.view.text.contains("<<<MARK>>>")); e.assertInSync()
    }

    @Test fun anEditCannotEscapeTheModelEvenIfTheWindowEdgeMoves() {
        val (e, _) = bigEnv(); val ref = StringBuilder(big); val rnd = Random(9)
        repeat(150) {
            when (rnd.nextInt(5)) {
                0 -> e.controller.requestShift(if (rnd.nextBoolean()) 1 else -1)
                1 -> e.controller.jumpToPermille(rnd.nextInt(1001))
                2 -> if (rnd.nextBoolean()) e.controller.jumpTop() else e.controller.jumpBottom()
                else -> {
                    val w = e.controller.activeWindow()!!; val pos = rnd.nextInt(e.view.text.length + 1)
                    val s = "x${it}\n"; e.view.typeAt(pos, s); ref.insert(w.start + pos, s)
                }
            }
            e.assertInSync()
        }
        assertEquals(ref.toString(), e.controller.activeText())
    }

    @Test fun theTextLimitIsExactlyTenMebibytes() {
        val s = FakeStorage()
        val ok = s.put("ok.txt", ByteArray(Limits.MAX_OPEN_BYTES.toInt()) { 'a'.code.toByte() })
        val tooBig = s.put("big.txt", ByteArray(Limits.MAX_OPEN_BYTES.toInt() + 1) { 'a'.code.toByte() })
        val e = env(storage = s)
        e.controller.onOpenPicked(tooBig)
        assertEquals("Couldn't open file", e.view.errors.single().first); assertTrue(e.view.errors.single().second.contains("10 MiB"))
        assertEquals(1, e.controller.tabCount)
        e.controller.onOpenPicked(ok)
        assertEquals(Limits.MAX_OPEN_BYTES.toInt(), e.controller.activeLength()); assertEquals(1, e.view.errors.size)
    }

    @Test fun totalMemoryAcrossTabsIsBounded() {
        val s = FakeStorage(); val e = env(storage = s)
        val docs = (0 until 4).map { s.put("d$it.txt", ByteArray(8_000_000) { i -> if (i % 80 == 79) '\n'.code.toByte() else 'a'.code.toByte() }) }
        for (i in 0 until 3) e.controller.onOpenPicked(docs[i])
        assertEquals(3, e.controller.tabCount); assertTrue(e.view.errors.isEmpty())
        e.controller.onOpenPicked(docs[3])
        assertEquals("Not enough room", e.view.errors.single().first); assertEquals(3, e.controller.tabCount)
        e.controller.closeTab(e.controller.tabId(0)!!); e.controller.onOpenPicked(docs[3]); assertEquals(3, e.controller.tabCount); assertEquals(1, e.view.errors.size)
    }

    @Test fun aSingleVeryLongLineLikeMinifiedJsonIsHandled() {
        val json = "{\"items\":[" + (0 until 400_000).joinToString(",") { "{\"id\":$it}" } + "]}"     // ~ 4.7 MB, no newline at all
        val s = FakeStorage(); val u = s.put("min.json", json.toByteArray()); val e = env(storage = s)
        e.controller.onOpenPicked(u)
        assertTrue(e.view.state!!.windowed); assertTrue("window ${e.view.text.length}", e.view.text.length < Limits.WINDOW_CHARS + 2 * Limits.ALIGN_SCAN)
        e.controller.jumpToPermille(500); e.assertInSync()
        val w = e.controller.activeWindow()!!; e.view.typeAt(10, " "); e.assertInSync()
        e.controller.requestSave()
        assertEquals(json.substring(0, w.start + 10) + " " + json.substring(w.start + 10), String(s.files[u]!!))
    }

    @Test fun anUnsavedTenMebibyteDocumentSurvivesARestart() {
        val dir = File(tmp.root, "bigsess"); val s = FakeStorage(); val u = s.put("big.txt", big.toByteArray())
        val e1 = Env(s, dir); e1.controller.start(); e1.controller.onOpenPicked(u)
        e1.controller.jumpToPermille(900); val w = e1.controller.activeWindow()!!; e1.view.typeAt(100, "RESTART-MARK")
        val t0 = System.nanoTime(); e1.controller.onPause(); val t1 = System.nanoTime()
        val e2 = Env(s, dir); e2.controller.start(); val t2 = System.nanoTime()
        assertEquals(big.length + 12, e2.controller.activeLength()); assertTrue(e2.controller.isDirty(0))
        assertTrue(e2.controller.activeText()!!.contains("RESTART-MARK")); assertEquals(big.substring(0, w.start + 100), e2.controller.activeText()!!.substring(0, w.start + 100))
        assertTrue(e2.view.state!!.canUndo); e2.assertInSync()
        println("LARGE-RESTORE: pause flush of dirty 10 MB tab ${(t1 - t0) / 1_000_000} ms; restore (read + crc + history) ${(t2 - t1) / 1_000_000} ms")
    }

    @Test fun measuredPerformanceOfEverydayOperationsOnATenMebibyteFile() {
        val s = FakeStorage(); val u = s.put("big.txt", big.toByteArray()); val e = env(storage = s)
        fun ms(f: () -> Unit): Long { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1_000_000 }
        val open = ms { e.controller.onOpenPicked(u) }
        val bottom = ms { e.controller.jumpBottom() }
        val top = ms { e.controller.jumpTop() }
        val seek = ms { e.controller.jumpToPermille(333) }
        var typeTotal = 0L; repeat(500) { typeTotal += ms { e.view.typeAt(e.view.text.length / 2, "x") } }
        val shiftT = ms { repeat(50) { e.controller.requestShift(1) } }
        val find = ms { e.controller.find("line 99999 ", true, false, true) }
        val miss = ms { e.controller.find("no such text anywhere", true, false, true) }
        val replace = ms { e.controller.replaceAll("ünï", "uni", true, false) }
        val undoR = ms { e.controller.undo() }
        val save = ms { e.controller.requestSave() }
        println("LARGE-CONTROLLER (10 MB, ${e.controller.activeLength()} chars, JVM, in-memory storage): open+decode ${open} ms, jump bottom ${bottom} ms, jump top ${top} ms, seek ${seek} ms, " +
            "500 keystrokes ${typeTotal} ms total, 50 window shifts ${shiftT} ms, find hit ${find} ms, find miss ${miss} ms, replace-all(141k matches) ${replace} ms, undo of it ${undoR} ms, save+verify ${save} ms")
        assertTrue(open < 20_000 && bottom < 2_000 && seek < 2_000 && miss < 5_000 && replace < 15_000 && save < 20_000)
        assertEquals(SaveStatus.SAVED, e.view.state!!.status)
    }
}
