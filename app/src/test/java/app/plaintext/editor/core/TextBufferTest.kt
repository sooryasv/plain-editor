package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class TextBufferTest {
    @Test fun basicOps() {
        val b = TextBuffer.fromString("hello world")
        assertEquals(11, b.length); assertEquals('w', b[6])
        b.replace(5, 5, ","); assertEquals("hello, world", b.toString())
        b.replace(0, 5, "HI"); assertEquals("HI, world", b.toString())
        b.replace(b.length, b.length, "!"); assertEquals("HI, world!", b.toString())
        b.replace(0, b.length, ""); assertEquals("", b.toString()); assertEquals(0, b.length)
        assertEquals("", b.substring(0, 0))
    }

    @Test fun boundsAreChecked() {
        val b = TextBuffer.fromString("abc")
        try { b[3]; fail() } catch (e: IndexOutOfBoundsException) {}
        try { b[-1]; fail() } catch (e: IndexOutOfBoundsException) {}
        try { b.replace(2, 5, "x"); fail() } catch (e: IllegalArgumentException) {}
        try { b.substring(2, 1); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun randomEditsMatchStringBuilder() {
        for (seed in 1..60) {
            val rnd = Random(seed.toLong())
            val ref = StringBuilder(); val b = TextBuffer()
            repeat(400) {
                val len = ref.length
                val s = rnd.nextInt(len + 1); val e = minOf(len, s + rnd.nextInt(30))
                val ins = (0 until rnd.nextInt(40)).joinToString("") { listOf("a", "\n", "é", "😀", " ")[rnd.nextInt(5)] }
                ref.replace(s, e, ins); b.replace(s, e, ins)
            }
            assertEquals("seed $seed", ref.toString(), b.toString())
            assertEquals(ref.length, b.length)
            val i = rnd.nextInt(ref.length + 1); val j = i + rnd.nextInt(ref.length - i + 1)
            assertEquals(ref.substring(i, j), b.substring(i, j))
            assertEquals(Crc.ofString(ref.toString()), b.crc32())
        }
    }

    @Test fun searchHelpersAcrossTheGap() {
        val b = TextBuffer.fromString("line one\nline two\nline three")
        b.replace(9, 9, "")                      // moves the gap to the middle
        assertEquals(8, b.indexOf('\n', 0)); assertEquals(17, b.indexOf('\n', 9)); assertEquals(-1, b.indexOf('\n', 18))
        assertEquals(17, b.lastIndexOf('\n', 27)); assertEquals(8, b.lastIndexOf('\n', 16)); assertEquals(-1, b.lastIndexOf('\n', 7))
        assertEquals(0, b.lineStart(3)); assertEquals(9, b.lineStart(9)); assertEquals(9, b.lineStart(12)); assertEquals(18, b.lineStart(27))
        assertEquals(8, b.lineEnd(0)); assertEquals(17, b.lineEnd(12)); assertEquals(b.length, b.lineEnd(20))
    }

    @Test fun replaceMany() {
        val b = TextBuffer.fromString("a-b-c-d")
        b.replace(3, 3, "")
        b.replaceMany(intArrayOf(1, 3, 5), intArrayOf(1, 1, 1), 3) { "<$it>" }
        assertEquals("a<0>b<1>c<2>d", b.toString())
        b.replace(0, 0, "X"); assertEquals("Xa<0>b<1>c<2>d", b.toString())
    }

    @Test fun assignAndWrapAndCopy() {
        val b = TextBuffer.wrap("xyz".toCharArray().copyOf(10), 3)
        assertEquals("xyz", b.toString())
        b.replace(1, 2, "ABC"); assertEquals("xABCz", b.toString())
        b.assign("fresh".toCharArray(), 5); assertEquals("fresh", b.toString())
        val snap = b.copyChars(); b.replace(0, 1, "F"); assertEquals("fresh", String(snap)); assertEquals("Fresh", b.toString())
    }

    @Test fun tenMegabyteOperationsAreFast() {
        val n = 10 * 1024 * 1024
        val chars = TextBuffer.withSlack(n); for (i in 0 until n) chars[i] = if (i % 80 == 79) '\n' else ('a' + i % 26)
        val b = TextBuffer.wrap(chars, n)
        val t0 = System.nanoTime()
        val rnd = Random(1)
        repeat(2_000) { val p = rnd.nextInt(b.length); b.replace(p, minOf(p + 3, b.length), "xyz!") }
        val t1 = System.nanoTime()
        repeat(100_000) { val p = 5_000_000 + rnd.nextInt(200); b.replace(p, p, "k") }   // local typing: no gap moves
        val t2 = System.nanoTime()
        val crc = b.crc32(); val s = b.copyChars(); val t3 = System.nanoTime()
        assertEquals(b.length, s.size)
        println("BUFFER-10MiB: 2k random-position edits (each moves the gap ~20 MB) ${(t1 - t0) / 1_000_000} ms = ${(t1 - t0) / 2_000 / 1000} us/edit; 100k local inserts ${(t2 - t1) / 1_000_000} ms; crc+snapshot ${(t3 - t2) / 1_000_000} ms (crc=$crc)")
        assertTrue("random edits took ${(t1 - t0) / 1_000_000} ms", (t1 - t0) < 20_000_000_000L)
        assertTrue("local inserts took ${(t2 - t1) / 1_000_000} ms", (t2 - t1) < 2_000_000_000L)
    }
}
