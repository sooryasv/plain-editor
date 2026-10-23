package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test

class RecentFilesTest {
    private fun e(n: Int) = RecentEntry("content://p/doc/$n", "file $n.md", n.toLong())

    @Test fun mostRecentFirstNoDuplicatesCapped() {
        val r = RecentFiles(max = 3)
        r.add(e(1)); r.add(e(2)); r.add(e(3))
        val evicted = r.add(e(4))
        assertEquals(listOf(4, 3, 2).map { e(it).uri }, r.list().map { it.uri })
        assertEquals(listOf(e(1).uri), evicted.map { it.uri })
        assertTrue(r.add(e(3)).isEmpty())
        assertEquals(e(3).uri, r.list().first().uri); assertEquals(3, r.list().size)
    }

    @Test fun serializeRoundTripWithAwkwardNames() {
        val r = RecentFiles()
        r.add(RecentEntry("content://a/b%3Ac?x=1&y=2", "tab\tand\nnewline ünï 😀.py", 42))
        r.add(e(7))
        val back = RecentFiles.parse(r.serialize())
        assertEquals(r.list(), back.list())
    }

    @Test fun parseToleratesGarbage() {
        assertTrue(RecentFiles.parse(null).list().isEmpty())
        assertTrue(RecentFiles.parse("").list().isEmpty())
        assertEquals(1, RecentFiles.parse("junk\nonly\ttwo\n" + RecentFiles().also { it.add(e(1)) }.serialize()).list().size)
    }

    @Test fun removeAndClear() {
        val r = RecentFiles(); r.add(e(1)); r.add(e(2))
        r.remove(e(1).uri); assertEquals(1, r.list().size)
        assertEquals(1, r.clear().size); assertTrue(r.list().isEmpty())
    }
}
