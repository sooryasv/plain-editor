package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class UndoHistoryTest {
    /** Applies edits to a StringBuilder the same way MainActivity does. */
    private fun apply(sb: StringBuilder, entry: UndoHistory.Entry?, undo: Boolean) {
        val e = entry as UndoHistory.Edit
        if (undo) sb.replace(e.start, e.start + e.inserted.length, e.removed)
        else sb.replace(e.start, e.start + e.removed.length, e.inserted)
    }

    private fun type(h: UndoHistory, sb: StringBuilder, at: Int, s: String) {
        h.record(at, "", s); sb.insert(at, s)
    }

    @Test fun typingIsMergedAndUndoRestores() {
        var now = 0L
        val h = UndoHistory(clock = { now })
        val sb = StringBuilder()
        for ((i, c) in "hello".withIndex()) { type(h, sb, i, c.toString()); now += 100 }
        val e = h.popUndo()!!
        assertEquals("hello", (e as UndoHistory.Edit).inserted)
        apply(sb, e, true)
        assertEquals("", sb.toString()); assertFalse(h.canUndo); assertTrue(h.canRedo)
        apply(sb, h.popRedo()!!, false)
        assertEquals("hello", sb.toString())
    }

    @Test fun pauseStartsNewStep() {
        var now = 0L
        val h = UndoHistory(clock = { now })
        val sb = StringBuilder()
        type(h, sb, 0, "a"); now += 5000; type(h, sb, 1, "b")
        apply(sb, h.popUndo()!!, true)
        assertEquals("a", sb.toString())
    }

    @Test fun newlineBreaksMerge() {
        var now = 0L
        val h = UndoHistory(clock = { now })
        val sb = StringBuilder()
        type(h, sb, 0, "a"); now += 10; type(h, sb, 1, "\n"); now += 10; type(h, sb, 2, "b")
        apply(sb, h.popUndo()!!, true); assertEquals("a\n", sb.toString())
        apply(sb, h.popUndo()!!, true); assertEquals("a", sb.toString())
    }

    @Test fun newEditClearsRedo() {
        val h = UndoHistory(clock = { 0 })
        h.record(0, "", "a"); h.popUndo(); assertTrue(h.canRedo)
        h.record(0, "", "b"); assertFalse(h.canRedo)
    }

    @Test fun backspaceChainMerges() {
        var now = 0L
        val h = UndoHistory(clock = { now })
        val sb = StringBuilder("abcdef")
        for (pos in 5 downTo 3) { h.record(pos, sb.substring(pos, pos + 1), ""); sb.deleteCharAt(pos); now += 50 }
        assertEquals("abc", sb.toString())
        apply(sb, h.popUndo()!!, true)
        assertEquals("abcdef", sb.toString())
    }

    @Test fun noOpEditsAreIgnored() {
        val h = UndoHistory()
        h.record(3, "same", "same")
        assertFalse(h.canUndo)
    }

    @Test fun memoryIsBounded() {
        val h = UndoHistory(clock = { 0 }, maxChars = 1000, mergeWindowMs = -1)
        for (i in 0 until 100) h.record(i * 10, "", "0123456789")
        var steps = 0
        while (h.popUndo() != null) steps++
        assertTrue("kept $steps steps", steps in 1..100 && steps * 10 <= 1000 + 10)
    }

    /** Random edits; undoing everything must restore the empty doc, redoing everything the final doc. */
    @Test fun randomisedUndoRedoIsExact() {
        for (seed in 1..200) {
            val rnd = Random(seed.toLong())
            var now = 0L
            val h = UndoHistory(clock = { now }, mergeWindowMs = if (seed % 2 == 0) 1000 else -1)
            val sb = StringBuilder()
            val snapshots = ArrayList<String>()
            snapshots.add("")
            repeat(60) {
                now += rnd.nextInt(1500)
                val len = sb.length
                when (rnd.nextInt(3)) {
                    0 -> { val at = rnd.nextInt(len + 1); val s = randomText(rnd); h.record(at, "", s); sb.insert(at, s) }
                    1 -> if (len > 0) {
                        val at = rnd.nextInt(len); val n = 1 + rnd.nextInt(minOf(3, len - at))
                        h.record(at, sb.substring(at, at + n), ""); sb.delete(at, at + n)
                    }
                    else -> if (len > 0) {
                        val at = rnd.nextInt(len); val n = 1 + rnd.nextInt(minOf(3, len - at)); val s = randomText(rnd)
                        h.record(at, sb.substring(at, at + n), s); sb.replace(at, at + n, s)
                    }
                }
            }
            val finalText = sb.toString()
            var guard = 0
            while (true) { val e = h.popUndo() ?: break; apply(sb, e, true); guard++ }
            assertEquals("seed $seed undo-all", "", sb.toString())
            while (true) { val e = h.popRedo() ?: break; apply(sb, e, false); guard++ }
            assertEquals("seed $seed redo-all", finalText, sb.toString())
        }
    }

    private fun randomText(r: Random): String {
        val alphabet = listOf("a", "b", " ", "é", "😀", "\n", "{", "  ")
        return (0 until 1 + r.nextInt(4)).joinToString("") { alphabet[r.nextInt(alphabet.size)] }
    }
}

class UndoHistoryBulkAndPersistTest {
    private fun bulk(vararg starts: Int, removed: String = "ab", repl: String = "XYZ") =
        UndoHistory.Bulk(starts, Array(starts.size) { removed }, repl, 0)

    @Test fun bulkNewStartsAndMapForward() {
        val b = bulk(2, 10, 20)             // removed "ab" (2) -> "XYZ" (3): each match grows by 1
        assertArrayEquals(intArrayOf(2, 11, 22), b.newStarts())
        assertEquals(1, b.mapForward(1))
        assertEquals(2, b.mapForward(2))
        assertEquals(2, b.mapForward(3))     // inside a replaced range -> start of its replacement
        assertEquals(5, b.mapForward(4))     // after first match: +1
        assertEquals(23, b.mapForward(21).let { it + 1 }) // inside third match maps to its new start (22)
        assertEquals(31, b.mapForward(28))
    }

    @Test fun bulkTooLargeClearsHistory() {
        val h = UndoHistory(maxChars = 100)
        h.record(0, "", "a")
        val starts = IntArray(1000) { it * 3 }
        assertFalse(h.recordBulk(UndoHistory.Bulk(starts, Array(1000) { "q" }, "r", 0)))
        assertFalse(h.canUndo)
    }

    @Test fun bulkIsOneUndoStepAndClearsRedo() {
        val h = UndoHistory(clock = { 0 })
        h.record(0, "", "x"); h.popUndo(); assertTrue(h.canRedo)
        assertTrue(h.recordBulk(bulk(1, 5)))
        assertFalse(h.canRedo); assertTrue(h.popUndo() is UndoHistory.Bulk)
    }

    @Test fun serializeRoundTripKeepsNewestEntriesWithinBudget() {
        var now = 0L
        val h = UndoHistory(clock = { now }, mergeWindowMs = -1)
        for (i in 0 until 50) { h.record(i, "", "line $i é😀"); now += 10 }
        h.recordBulk(UndoHistory.Bulk(intArrayOf(1, 9), arrayOf("a", "a"), "bb", 0))
        val all = UndoHistory.deserialize(h.serialize())!!
        assertEquals(h.undoCount, all.undoCount)
        val e = all.popUndo(); assertTrue(e is UndoHistory.Bulk)
        assertArrayEquals(intArrayOf(1, 9), (e as UndoHistory.Bulk).starts); assertEquals("bb", e.replacement)
        val small = UndoHistory.deserialize(h.serialize(budgetChars = 60))!!
        assertTrue(small.undoCount in 1 until h.undoCount)
        small.popUndo() // newest entries are the ones kept
        val next = small.popUndo() as UndoHistory.Edit
        assertEquals("line 49 é😀", next.inserted)
    }

    @Test fun restoredEntriesNeverMergeWithNewTyping() {
        val r = UndoHistory.deserialize(UndoHistory(clock = { 0 }).also { it.record(0, "", "a") }.serialize(), clock = { 5 })!!
        r.record(1, "", "b")
        assertEquals(2, r.undoCount)
    }

    @Test fun corruptDataIsRejectedNotThrown() {
        val good = UndoHistory(clock = { 0 }).also { it.record(0, "", "hello") }.serialize()
        assertNull(UndoHistory.deserialize(byteArrayOf(1, 2, 3)))
        assertNull(UndoHistory.deserialize(good.copyOf(good.size - 3)))
        val bad = good.copyOf(); bad[7] = 99
        UndoHistory.deserialize(bad) // must not throw (null or a history)
    }
}
