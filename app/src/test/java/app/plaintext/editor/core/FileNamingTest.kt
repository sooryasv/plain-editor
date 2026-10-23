package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test

class FileNamingTest {
    @Test fun extensionIsKeptAndNeverDuplicated() {
        for (e in listOf("md", "txt", "yml", "yaml", "py")) {
            assertEquals("notes.$e", FileNaming.ensureExtension("notes.$e"))
            assertEquals("notes.$e", FileNaming.ensureExtension("notes.$e.$e"))
        }
        assertEquals("a.b.md", FileNaming.ensureExtension("a.b.md"))
        assertEquals("script.PY", FileNaming.ensureExtension("script.PY"))
    }

    @Test fun defaultExtensionOnlyWhenMissing() {
        assertEquals("notes.txt", FileNaming.ensureExtension("notes"))
        assertEquals("notes.md", FileNaming.ensureExtension("notes", "md"))
        assertEquals(".gitignore.txt", FileNaming.ensureExtension(".gitignore"))
        assertEquals("trailing.txt", FileNaming.ensureExtension("trailing."))
        assertEquals("Makefile.txt", FileNaming.ensureExtension("Makefile"))
    }

    @Test fun otherPlainTextExtensionsAreLeftAlone() {
        assertEquals("data.csv", FileNaming.ensureExtension("data.csv"))
        assertEquals("conf.toml", FileNaming.ensureExtension("conf.toml"))
    }

    @Test fun sanitizeRemovesPathsAndControlChars() {
        assertEquals("a_b_c.txt", FileNaming.sanitize("a/b\\c.txt"))
        assertEquals("a_b.txt", FileNaming.sanitize("a\nb.txt"))
        assertEquals("Untitled", FileNaming.sanitize("   "))
        assertTrue(FileNaming.sanitize("x".repeat(500)).length <= 120)
    }

    @Test fun withExtensionReplaces() {
        assertEquals("notes.md", FileNaming.withExtension("notes.txt", "md"))
        assertEquals("notes.py", FileNaming.withExtension("notes", "py"))
        assertEquals("a.b.yml", FileNaming.withExtension("a.b.md", "yml"))
    }

    @Test fun typeLabels() {
        assertEquals("Markdown", FileNaming.typeLabel("a.md"))
        assertEquals("Plain text", FileNaming.typeLabel("a.txt"))
        assertEquals("YAML", FileNaming.typeLabel("a.yml"))
        assertEquals("YAML", FileNaming.typeLabel("a.YAML"))
        assertEquals("Python", FileNaming.typeLabel("a.py"))
        assertEquals("Text (.csv)", FileNaming.typeLabel("a.csv"))
        assertEquals("Plain text", FileNaming.typeLabel("README"))
    }
}
