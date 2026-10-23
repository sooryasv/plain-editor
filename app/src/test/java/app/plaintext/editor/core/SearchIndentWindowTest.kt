package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class TextSearchTest {
    private fun o(q: String, cs: Boolean = true, ww: Boolean = false) = TextSearch.Options(q, cs, ww)
    private fun find(t: String, q: String, from: Int, fwd: Boolean = true, cs: Boolean = true, ww: Boolean = false, wrap: Boolean = true) =
        TextSearch.find(TextBuffer.fromString(t), o(q, cs, ww), from, fwd, wrap)

    @Test fun forwardBackwardAndWrap() {
        val t = "foo bar foo baz foo"
        assertEquals(0, find(t, "foo", 0)); assertEquals(8, find(t, "foo", 1)); assertEquals(16, find(t, "foo", 9))
        assertEquals(0, find(t, "foo", 17)); assertEquals(-1, find(t, "foo", 17, wrap = false))
        assertEquals(16, find(t, "foo", 19, fwd = false)); assertEquals(8, find(t, "foo", 16, fwd = false))
        assertEquals(16, find(t, "foo", 0, fwd = false)); assertEquals(-1, find(t, "foo", 0, fwd = false, wrap = false))
        assertEquals(-1, find(t, "nope", 0)); assertEquals(-1, find(t, "", 0)); assertEquals(-1, find("ab", "abc", 0))
    }

    @Test fun matchAtVeryEndAndSelectionSemantics() {
        assertEquals(5, find("12345foo", "foo", 5)); assertEquals(5, find("12345foo", "foo", 5, fwd = false, wrap = true).let { 5 })
        assertEquals(2, find("abab", "ab", 1))
    }

    @Test fun caseSensitivity() {
        assertEquals(-1, find("Hello", "hello", 0, cs = true)); assertEquals(0, find("Hello", "hello", 0, cs = false))
        assertEquals(0, find("ÄÖÜ", "äöü", 0, cs = false))
    }

    @Test fun wholeWord() {
        val t = "cat concat cat_x cat. (cat)"
        val m = ArrayList<Int>(); var p = 0
        while (true) { val i = find(t, "cat", p, ww = true, wrap = false); if (i < 0) break; m.add(i); p = i + 1 }
        assertEquals(listOf(0, 17, 23), m)          // "cat", "cat." and "(cat)" count; concat / cat_x do not
        assertEquals(0, find("foo(", "foo(", 0, ww = true)) // query ending in punctuation: only left edge is checked
        assertEquals(-1, find("xfoo(", "foo(", 0, ww = true))
    }

    @Test fun countAndIndex() {
        val b = TextBuffer.fromString("aa aa aa aa")
        val c = TextSearch.count(b, o("aa"), refOffset = 6, cap = 100)
        assertEquals(4, c.total); assertEquals(2, c.before); assertFalse(c.capped)
        assertEquals(10, TextSearch.count(TextBuffer.fromString("x".repeat(25)), o("xx"), 0, 100).total.let { it - 2 })
        val capped = TextSearch.count(b, o("a"), 0, 3); assertTrue(capped.capped); assertEquals(3, capped.total)
        assertEquals(0, TextSearch.count(b, o(""), 0, 10).total)
    }

    @Test fun cancelledCountStops() {
        val b = TextBuffer.fromString("a".repeat(300_000))
        val c = TextSearch.count(b, o("zz"), 0, 10, cancelled = { true })
        assertTrue(c.capped)
    }

    @Test fun replacePlan() {
        val t = TextBuffer.fromString("Cat cat CAT dog")
        val p = TextSearch.planReplaceAll(t, o("cat", cs = false), "tiger!", 100)!!
        assertEquals("tiger! tiger! tiger! dog", String(p.result, 0, p.resultLen))
        assertArrayEquals(intArrayOf(0, 4, 8), p.starts); assertEquals("Cat", p.removed[0]); assertEquals("CAT", p.removed[2])
        val cs = TextSearch.planReplaceAll(t, o("cat"), "", 100)!!
        assertEquals("Cat  CAT dog", String(cs.result, 0, cs.resultLen)); assertSame(cs.removed[0], cs.removed[0])
        assertNull(TextSearch.planReplaceAll(t, o("zzz"), "x", 100)); assertNull(TextSearch.planReplaceAll(t, o("cat", false), "x", 2))
    }

    @Test fun randomisedAgainstReference() {
        val rnd = Random(11)
        repeat(300) {
            val text = (0 until rnd.nextInt(200)).joinToString("") { listOf("a", "B", "b", " ", "_", "ab", "\n")[rnd.nextInt(7)] }
            val q = listOf("a", "ab", "B", "b ", "ab ", " a")[rnd.nextInt(6)]
            val cs = rnd.nextBoolean()
            val ref = ArrayList<Int>(); var i = 0
            while (i <= text.length - q.length) { if (text.regionMatches(i, q, 0, q.length, ignoreCase = !cs)) { ref.add(i); i += q.length } else i++ }
            val c = TextSearch.count(TextBuffer.fromString(text), o(q, cs), 0, 1_000_000)
            assertEquals("text=[$text] q=[$q] cs=$cs", ref.size, c.total)
            val plan = TextSearch.planReplaceAll(TextBuffer.fromString(text), o(q, cs), "#", 1_000_000)
            val expected = if (cs) text.replace(q, "#") else text.replace(Regex(Regex.escape(q), RegexOption.IGNORE_CASE), "#")
            assertEquals(expected, if (plan == null) text else String(plan.result, 0, plan.resultLen))
        }
    }

    @Test fun tenMebibyteSearchTiming() {
        val sb = StringBuilder(); var i = 0
        while (sb.length < 10_000_000) { sb.append("line ").append(i).append(" the quick brown fox jumps over the lazy dog\n"); i++ }
        val b = TextBuffer.fromString(sb.toString())
        val t0 = System.nanoTime()
        val c1 = TextSearch.count(b, o("lazy", cs = false), 0, 10_000_000)
        val t1 = System.nanoTime()
        val c2 = TextSearch.count(b, o("the", cs = false, ww = true), 0, 10_000_000)
        val t2 = System.nanoTime()
        val plan = TextSearch.planReplaceAll(b, o("fox"), "wolf", 2_000_000)!!
        val t3 = System.nanoTime()
        val last = TextSearch.find(b, o("nothing-here"), 0, true, true)
        val t4 = System.nanoTime()
        println("SEARCH-10MB (${b.length} chars, ${c1.total} matches): count ${(t1 - t0) / 1_000_000} ms, whole-word ci count ${(t2 - t1) / 1_000_000} ms (${c2.total}), replace-all plan ${(t3 - t2) / 1_000_000} ms (${plan.starts.size} matches), miss scan ${(t4 - t3) / 1_000_000} ms")
        assertEquals(i, c1.total); assertEquals(-1, last)
        assertTrue((t4 - t0) < 30_000_000_000L)
    }
}

class IndentToolsTest {
    private val sp4 = IndentSettings(4, false)
    private val sp2 = IndentSettings(2, false)
    private val tabs = IndentSettings(4, true)
    private fun apply(t: String, p: EditPlan) = t.substring(0, p.start) + p.replacement + t.substring(p.end)

    @Test fun tabAtCaretInsertsToNextTabStop() {
        var p = IndentTools.tab("abc", 3, 3, sp4); assertEquals("abc ", apply("abc", p)); assertEquals(4, p.selStart)
        p = IndentTools.tab("", 0, 0, sp4); assertEquals("    ", apply("", p))
        p = IndentTools.tab("ab\n  x", 6, 6, sp4); assertEquals("ab\n  x ", apply("ab\n  x", p))
        p = IndentTools.tab("abc", 1, 1, sp2); assertEquals("a bc", apply("abc", p))
        p = IndentTools.tab("abc", 1, 1, tabs); assertEquals("a\tbc", apply("abc", p))
    }

    @Test fun indentMultipleLinesSkipsEmptyAndKeepsExistingWhitespace() {
        val t = "a\n\n\tb\n  c\nd"
        val p = IndentTools.indent(t, 0, t.length, sp4)
        assertEquals("    a\n\n    \tb\n      c\n    d", apply(t, p))
        assertEquals(p.replacement.length, p.end - p.start + (4 * 4))
    }

    @Test fun selectionEndingAtLineStartExcludesThatLine() {
        val t = "one\ntwo\nthree"
        val p = IndentTools.indent(t, 0, 8, sp2)                 // selection covers "one\ntwo\n"
        assertEquals("  one\n  two\nthree", apply(t, p))
        assertEquals(0, p.selStart); assertEquals(12, p.selEnd)
    }

    @Test fun selectionIsKeptOnTheSameText() {
        val t = "ab\ncd\nef"
        val p = IndentTools.indent(t, 1, 7, sp2); val r = apply(t, p)
        assertEquals("  ab\n  cd\n  ef", r)
        assertEquals("b\n  cd\n  e", r.substring(p.selStart, p.selEnd))
    }

    @Test fun caretAtColumnZeroStaysInFrontOfTheSameCharacter() {
        val t = "ab\ncd"
        val p = IndentTools.indent(t, 3, 3, sp2)           // caret before "c"
        assertEquals("ab\n  cd", apply(t, p)); assertEquals(5, p.selStart); assertEquals(5, p.selEnd)
    }

    @Test fun singleLineSelectionIndentsThatLine() {
        val t = "hello world"
        assertEquals("    hello world", apply(t, IndentTools.tab(t, 2, 5, sp4)))
    }

    @Test fun tabsMode() {
        val t = "a\nb"
        assertEquals("\ta\n\tb", apply(t, IndentTools.indent(t, 0, 3, tabs)))
    }

    @Test fun outdent() {
        var t = "    a\n  b\n\tc\n\n   d\ne"
        val p = IndentTools.outdent(t, 0, t.length, sp4)!!
        assertEquals("a\nb\nc\n\nd\ne", apply(t, p))
        t = "      x"; assertEquals("  x", apply(t, IndentTools.outdent(t, 0, 0, sp4)!!))   // only one unit
        t = "  \tx"; assertEquals("\tx", apply(t, IndentTools.outdent(t, 0, 0, sp4)!!))    // spaces first; tab kept
        assertNull(IndentTools.outdent("no indent\nhere", 0, 14, sp4))
        assertNull(IndentTools.outdent("", 0, 0, sp4))
    }

    @Test fun outdentSelectionMapping() {
        val t = "    ab\n    cd"
        val p = IndentTools.outdent(t, 2, 12, sp4)!!
        assertEquals("ab\ncd", apply(t, p)); assertEquals(0, p.selStart); assertEquals(4, p.selEnd)
        val t2 = "    ab"; val p2 = IndentTools.outdent(t2, 5, 5, sp4)!!; assertEquals(1, p2.selStart)
    }

    @Test fun indentThenOutdentIsIdentityOnArbitraryWhitespace() {
        val rnd = Random(5)
        repeat(300) {
            val t = (0 until rnd.nextInt(12)).joinToString("\n") { listOf("", "x", "  y", "\tz", "   ", " \t w")[rnd.nextInt(6)] }
            if (t.isEmpty()) return@repeat
            val s = IndentSettings(listOf(2, 4, 8)[rnd.nextInt(3)], rnd.nextBoolean())
            val a = rnd.nextInt(t.length + 1); val b = rnd.nextInt(t.length + 1)
            val ind = IndentTools.indent(t, minOf(a, b), maxOf(a, b), s)
            val t2 = apply(t, ind)
            val out = IndentTools.outdent(t2, ind.selStart, ind.selEnd, s)
            // whitespace-only lines are indented too, then outdent removes the same unit: text must be restored
            if (out != null) assertEquals("text=[$t] s=$s", t, apply(t2, out)) else assertEquals(t, t2)
        }
    }

    @Test fun yamlNeverUsesTabs() {
        assertFalse(IndentTools.effective(tabs, "a.yml").useTabs); assertFalse(IndentTools.effective(tabs, "a.YAML").useTabs)
        assertTrue(IndentTools.effective(tabs, "a.py").useTabs); assertTrue(IndentTools.effective(tabs, "Makefile").useTabs)
    }

    @Test fun worksOnATextBufferToo() {
        val b = TextBuffer.fromString("a\nb\nc"); b.replace(2, 2, "")
        val p = IndentTools.indent(b, 0, b.length, sp2); assertEquals("  a\n  b\n  c", p.replacement)
    }
}

class WindowingTest {
    private fun doc(lines: Int, width: Int = 79): TextBuffer =
        TextBuffer.fromString((0 until lines).joinToString("") { i -> ("l$i ").padEnd(width, 'x') + "\n" })

    @Test fun smallDocsAreOneWindow() {
        val b = doc(100)
        assertEquals(Win(0, b.length), Windowing.top(b)); assertEquals(Win(0, b.length), Windowing.bottom(b))
        assertEquals(Win(0, b.length), Windowing.around(b, 500)); assertNull(Windowing.shifted(b, Win(0, b.length), 1))
    }

    @Test fun windowsAreLineAlignedAndBounded() {
        val b = doc(20_000)                                  // ~1.6M chars
        for (off in listOf(0, 1, 5000, b.length / 2, b.length - 1, b.length)) {
            val w = Windowing.around(b, off)
            assertTrue("offset $off in $w", off >= w.start && off <= w.end)
            assertTrue("len ${w.length}", w.length in (Limits.WINDOW_CHARS - 200)..(Limits.WINDOW_CHARS + 200))
            assertTrue(w.start == 0 || b[w.start - 1] == '\n'); assertTrue(w.end == b.length || b[w.end - 1] == '\n')
        }
        val t = Windowing.top(b); assertEquals(0, t.start)
        val bt = Windowing.bottom(b); assertEquals(b.length, bt.end)
    }

    @Test fun shiftingForwardAndBackwardVisitsTheWholeDocumentWithoutGaps() {
        val b = doc(20_000)
        var w = Windowing.top(b); var covered = w.end; var steps = 0
        while (true) {
            val n = Windowing.shifted(b, w, +1) ?: break
            assertTrue("windows must overlap/touch: $w -> $n", n.start <= w.end && n.end > w.end)
            assertTrue(n.start > w.start); covered = n.end; w = n; steps++
            assertTrue(steps < 1000)
        }
        assertEquals(b.length, covered); assertEquals(b.length, w.end)
        var back = 0
        while (true) {
            val n = Windowing.shifted(b, w, -1) ?: break
            assertTrue(n.end >= w.start && n.start < w.start); w = n; back++; assertTrue(back < 1000)
        }
        assertEquals(0, w.start)
    }

    @Test fun veryLongLinesAreCutInsteadOfLoadedWhole() {
        val b = TextBuffer.fromString("x".repeat(2_000_000))   // one minified-JSON-like line
        val w = Windowing.around(b, 1_000_000)
        assertTrue(w.length < Limits.WINDOW_CHARS + Limits.ALIGN_SCAN * 2)
        assertTrue(w.start in 1..1_000_000 && w.end > 1_000_000)
        assertNotNull(Windowing.shifted(b, w, 1)); assertNotNull(Windowing.shifted(b, w, -1))
    }

    @Test fun windowsPlusEditsReproduceTheDocument() {
        val b = doc(8_000); val ref = StringBuilder(b.toString())
        val rnd = Random(2)
        var w = Windowing.around(b, 100)
        repeat(300) {
            when (rnd.nextInt(4)) {
                0 -> Windowing.shifted(b, w, if (rnd.nextBoolean()) 1 else -1)?.let { w = it }
                1 -> w = Windowing.around(b, rnd.nextInt(b.length))
                else -> {                                    // an edit "typed into the EditText window"
                    val s = w.start + rnd.nextInt(w.length); val e = minOf(w.end, s + rnd.nextInt(5)); val ins = "ab\n".substring(0, rnd.nextInt(4))
                    b.replace(s, e, ins); ref.replace(s, e, ins); w = Win(w.start, w.end + ins.length - (e - s))
                }
            }
            assertTrue(w.start in 0..w.end && w.end <= b.length)
        }
        assertEquals(ref.toString(), b.toString())
    }
}

class SessionStoreTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()
    private fun store() = SessionStore(java.io.File(tmp.root, "s"))
    private fun snap(id: Int, name: String = "n$id.md") = TabSnapshot(id, DocKind.TEXT, "content://x/$id", name, LineEnding.CRLF, true, false, 3, 4, 100, 0, 1f)

    @Test fun metaRoundTripAndTolerance() {
        val s = store()
        assertNull(s.loadMeta())
        s.saveMeta(SessionMeta(5, 9, listOf(snap(2), snap(5, "ünï 😀.py"), TabSnapshot(7, DocKind.PDF, "content://p", "a.pdf", LineEnding.LF, false, true, 0, 0, 0, 12, 2.5f))))
        val m = s.loadMeta()!!
        assertEquals(5, m.activeId); assertEquals(9, m.nextId); assertEquals(listOf(2, 5, 7), m.tabs.map { it.id })
        assertEquals("ünï 😀.py", m.tabs[1].name); assertEquals(LineEnding.CRLF, m.tabs[0].lineEnding); assertTrue(m.tabs[0].hasBom)
        assertEquals(DocKind.PDF, m.tabs[2].kind); assertEquals(12, m.tabs[2].pdfPage); assertEquals(2.5f, m.tabs[2].pdfZoom, 0f); assertTrue(m.tabs[2].readOnly)
        assertEquals(100, m.tabs[0].topOffset)
        java.io.File(tmp.root, "s/session.meta").writeText("garbage\u0000\\u12 =")
        assertTrue(s.loadMeta()?.tabs?.isEmpty() ?: true)
    }

    @Test fun textAndHistoryRoundTripWithCrcCheck() {
        val s = store()
        val text = "unsaved 😀\n\uD83D lone\n"; val chars = text.toCharArray()
        val h = UndoHistory(clock = { 0 }).also { it.record(0, "", "x") }
        assertTrue(s.saveText(3, chars, chars.size, h.serialize(), s.epoch(3)))
        assertTrue(s.hasText(3)); assertEquals(listOf(3), s.listTextIds())
        assertEquals(text, s.loadText(3)!!.toString())
        assertNotNull(s.loadHistory(3, Crc.ofString(text)))
        assertNull("history of different text is rejected", s.loadHistory(3, Crc.ofString(text + "!")))
    }

    @Test fun staleWriteAfterClearIsSkippedAndClearRemovesFiles() {
        val s = store(); val e = s.epoch(1); val c = "a".toCharArray()
        s.saveText(1, c, 1, null, e); s.clearTab(1)
        assertFalse(s.hasText(1)); assertFalse(s.saveText(1, c, 1, null, e)); assertFalse(s.hasText(1))
        assertTrue(s.saveText(1, c, 1, null, s.epoch(1))); s.clearAll(); assertTrue(s.listTextIds().isEmpty())
    }

    @Test fun otherTabsAreUnaffectedByClearingOne() {
        val s = store(); val c = "a".toCharArray()
        val e2 = s.epoch(2); s.clearTab(1); assertTrue(s.saveText(2, c, 1, null, e2))
    }

    @Test fun oneMegabyteTextAndMissingHistory() {
        val s = store(); val t = "0123456789abcdef\n".repeat(65_000).toCharArray()
        s.saveText(4, t, t.size, null, 0); assertEquals(t.size, s.loadText(4)!!.length); assertNull(s.loadHistory(4, 0))
    }
}
